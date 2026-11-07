#include "core/ProfileStore.h"
#include <QApplication>
#include <QCommandLineParser>
#include <QDialog>
#include <QDialogButtonBox>
#include <QDir>
#include <QFileDialog>
#include <QFileInfo>
#include <QFormLayout>
#include <QHBoxLayout>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLabel>
#include <QLineEdit>
#include <QListWidget>
#include <QLockFile>
#include <QMessageBox>
#include <QProcess>
#include <QPushButton>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QTimer>
#include <QUuid>
#include <QVBoxLayout>
#include <functional>
#include <stdexcept>

static QString describeError(const std::exception &error) {
    const QByteArray code(error.what());
    if (code == "invalid_server") return "请填写 HTTPS 域名或 IP，可带端口；不要包含账号、路径或查询参数。";
    if (code == "invalid_profile_name") return "请填写 1–128 个字符的配置名称，不要包含控制字符。";
    if (code == "unsupported_schema") return "此配置格式暂不支持，请使用兼容版本的配置文件。";
    if (code == "unknown_field") return "配置包含未识别的字段；为防止丢失数据，未执行导入。";
    if (code == "certificate_review_required") return "配置要求忽略证书错误。本预览不会导入这项信任决定。";
    if (code == "invalid_profile_id") return "配置标识无效或重复，请检查文件。";
    if (code == "config_too_large" || code == "too_many_profiles") return "配置超出大小或数量限制（256 KiB / 128 个服务器）。";
    if (code.startsWith("config_")) return "无法读写配置文件，请检查文件权限和可用空间。";
    return "配置格式无效，请检查文件内容。";
}

int main(int argc, char **argv) {
    QApplication app(argc, argv);
    app.setApplicationName("GeekOCTunnel-Desktop-Preview");
    app.setOrganizationName("GeekOCTunnel");
    QCommandLineParser parser;
    parser.addHelpOption();
    parser.addOption({"smoke-test", "Open and close the UI using disposable configuration."});
    parser.process(app);
    QTemporaryDir temporary;
    const auto directory = parser.isSet("smoke-test") ? temporary.path()
        : QStandardPaths::writableLocation(QStandardPaths::AppConfigLocation);
    if (directory.isEmpty() || !QDir().mkpath(directory)) return 1;
    QLockFile lock(directory + "/profiles.lock");
    if (!lock.tryLock(0)) {
        QMessageBox::warning(nullptr, "GeekOCTunnel", "配置正在由另一个桌面预览实例使用。");
        return 1;
    }
    const auto configPath = directory + "/profiles.json";
    QList<geekoc::Profile> profiles;
    try { profiles = geekoc::loadProfiles(configPath); }
    catch (const std::exception &error) {
        QMessageBox::critical(nullptr, "无法读取配置", describeError(error) + "\n原文件已保留。");
        return 1;
    }

    QWidget window;
    window.setWindowTitle("GeekOCTunnel · 桌面开发预览");
    window.resize(660, 440);
    auto *layout = new QVBoxLayout(&window);
    auto *heading = new QLabel("GeekOCTunnel", &window);
    auto font = heading->font();
    font.setPointSize(22);
    font.setBold(true);
    heading->setFont(font);
    layout->addWidget(heading);
    auto *description = new QLabel("Windows / Linux 桌面开发预览\n管理服务器配置、检查 OpenConnect 基础能力。VPN 连接尚未开放。", &window);
    description->setWordWrap(true);
    layout->addWidget(description);
    auto *list = new QListWidget(&window);
    list->setAccessibleName("服务器配置");
    layout->addWidget(list, 1);
    auto *actions = new QHBoxLayout;
    layout->addLayout(actions);
    auto button = [&](const QString &label, const std::function<void()> &action) {
        auto *b = new QPushButton(label, &window);
        actions->addWidget(b);
        QObject::connect(b, &QPushButton::clicked, &window, action);
        return b;
    };
    auto refresh = [&] {
        list->clear();
        for (const auto &profile : profiles) list->addItem(profile.name + "  ·  " + profile.server);
    };
    auto errorMessage = [&](const std::exception &error) {
        QMessageBox::warning(&window, "操作未完成", describeError(error) + "\n配置未更改。");
    };
    auto commit = [&](const QList<geekoc::Profile> &next) {
        geekoc::saveProfiles(configPath, next);
        profiles = next;
        refresh();
    };
    auto edit = [&](int row) {
        QDialog dialog(&window);
        dialog.setWindowTitle(row < 0 ? "新增服务器" : "编辑服务器");
        dialog.setMinimumWidth(460);
        auto *form = new QFormLayout(&dialog);
        QLineEdit name, server;
        name.setMaxLength(128);
        server.setMaxLength(2048);
        server.setPlaceholderText("https://vpn.example.com:443");
        if (row >= 0) { name.setText(profiles[row].name); server.setText(profiles[row].server); }
        form->addRow("名称(&N)", &name);
        form->addRow("HTTPS 服务器(&S)", &server);
        auto *hint = new QLabel("证书校验默认启用。本预览不接收或保存密码、令牌及私钥。");
        hint->setWordWrap(true);
        form->addRow(hint);
        QDialogButtonBox buttons(QDialogButtonBox::Save | QDialogButtonBox::Cancel);
        buttons.button(QDialogButtonBox::Save)->setText("保存");
        buttons.button(QDialogButtonBox::Cancel)->setText("取消");
        form->addRow(&buttons);
        QObject::connect(&buttons, &QDialogButtonBox::rejected, &dialog, &QDialog::reject);
        QObject::connect(&buttons, &QDialogButtonBox::accepted, &dialog, [&] {
            try {
                auto next = profiles;
                geekoc::Profile profile{row < 0 ? QUuid::createUuid().toString(QUuid::WithoutBraces) : profiles[row].id,
                                        name.text().trimmed(), geekoc::normalizeServer(server.text())};
                if (row < 0) next.append(profile); else next[row] = profile;
                commit(next);
                dialog.accept();
            } catch (const std::exception &error) { errorMessage(error); }
        });
        dialog.exec();
    };
    button("新增", [&] { edit(-1); });
    auto *editButton = button("编辑", [&] { if (list->currentRow() >= 0) edit(list->currentRow()); });
    auto *removeButton = button("删除", [&] {
        const auto row = list->currentRow();
        if (row < 0 || QMessageBox::question(&window, "删除配置", "删除选中的服务器配置？") != QMessageBox::Yes) return;
        try { auto next = profiles; next.removeAt(row); commit(next); }
        catch (const std::exception &error) { errorMessage(error); }
    });
    button("导入", [&] {
        const auto path = QFileDialog::getOpenFileName(&window, "导入配置", {}, "JSON (*.json)");
        if (path.isEmpty()) return;
        try {
            if (!QFileInfo(path).isFile()) throw std::runtime_error("config_read_failed");
            const auto next = geekoc::loadProfiles(path);
            if (QMessageBox::question(&window, "替换配置", QString("用文件中的 %1 个服务器替换当前列表？").arg(next.size())) == QMessageBox::Yes)
                commit(next);
        } catch (const std::exception &error) { errorMessage(error); }
    });
    button("导出", [&] {
        if (QMessageBox::warning(&window, "导出内容", "导出包含服务器名称和地址，可能暴露组织网络信息。确定继续？",
                                 QMessageBox::Yes | QMessageBox::No, QMessageBox::No) != QMessageBox::Yes) return;
        const auto path = QFileDialog::getSaveFileName(&window, "导出配置", "profiles.json", "JSON (*.json)");
        if (path.isEmpty()) return;
        try { geekoc::saveProfiles(path, profiles); }
        catch (const std::exception &error) { errorMessage(error); }
    });
    editButton->setEnabled(false);
    removeButton->setEnabled(false);
    QObject::connect(list, &QListWidget::currentRowChanged, &window, [&](int row) {
        editButton->setEnabled(row >= 0);
        removeButton->setEnabled(row >= 0);
    });
    auto *status = new QLabel("未连接 · 当前预览不会修改系统网卡、路由或 DNS。", &window);
    status->setTextFormat(Qt::PlainText);
    status->setWordWrap(true);
    status->setTextInteractionFlags(Qt::TextSelectableByMouse);
    layout->addWidget(status);
    auto *probeButton = new QPushButton("检查 OpenConnect", &window);
    layout->addWidget(probeButton);
    QProcess probe;
    QTimer timeout;
    timeout.setSingleShot(true);
    QObject::connect(&timeout, &QTimer::timeout, &window, [&] { probe.kill(); });
    QObject::connect(probeButton, &QPushButton::clicked, &window, [&] {
        QStringList arguments;
#ifdef _WIN32
        const auto path = QFileDialog::getOpenFileName(&window, "选择可信安装中的 OpenConnect 库", {}, "OpenConnect (libopenconnect-5.dll)");
        if (path.isEmpty()) return;
        arguments << "--library" << path;
#endif
        probeButton->setEnabled(false);
        status->setText("正在检查库加载、取消和设备接口……");
        probe.setProgram(QCoreApplication::applicationDirPath() + "/geekoctunnel-probe"
#ifdef _WIN32
                         ".exe"
#endif
        );
        probe.setArguments(arguments);
        probe.start();
        timeout.start(15000);
    });
    QObject::connect(&probe, &QProcess::errorOccurred, &window, [&](QProcess::ProcessError) {
        timeout.stop();
        probeButton->setEnabled(true);
        status->setText("检查进程无法启动或已中止；请检查安装是否完整。");
    });
    QObject::connect(&probe, qOverload<int, QProcess::ExitStatus>(&QProcess::finished), &window,
        [&](int code, QProcess::ExitStatus exitStatus) {
            timeout.stop();
            probeButton->setEnabled(true);
            const auto bytes = probe.readAllStandardOutput();
            const auto report = bytes.size() < 16384 ? QJsonDocument::fromJson(bytes).object() : QJsonObject{};
            if (exitStatus == QProcess::NormalExit && code == 0 && report.value("schemaVersion").toInt() == 1
                && report.value("ok").toBool())
                status->setText(QString("OpenConnect %1 · 本地取消测试通过（%2 ms）。\n这不代表 VPN 连接、Wintun 或系统网络恢复已验证。")
                                .arg(report.value("openconnectVersion").toString()).arg(report.value("cancelMilliseconds").toDouble()));
            else status->setText("基础能力检查未通过。可运行 geekoctunnel-probe 查看不含凭据的错误码。");
        });
    QObject::connect(&app, &QCoreApplication::aboutToQuit, [&] {
        if (probe.state() != QProcess::NotRunning) { probe.kill(); probe.waitForFinished(2000); }
    });
    refresh();
    window.show();
    if (parser.isSet("smoke-test")) QTimer::singleShot(250, &app, &QCoreApplication::quit);
    return app.exec();
}
