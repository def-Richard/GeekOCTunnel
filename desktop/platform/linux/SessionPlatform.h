#pragma once
#include "HelperSession.h"
#include <QFileInfo>
#include <csignal>

namespace geekoc::vpn {
using CommandSocket = int;
constexpr int InvalidCommand = -1;
class StopEvent : public Handle {
public:
    StopEvent() : Handle(eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK)) { check(valid(), "cancel_setup_failed"); }
    void set() { uint64_t value=1; const auto ignored=write(get(),&value,sizeof(value)); (void)ignored; }
};
inline QString openconnectLibrary() {
    return QCoreApplication::applicationDirPath() + "/libopenconnect.so.5";
}
inline void initializePlatform() { signal(SIGPIPE,SIG_IGN); }
inline void writeCommand(int fd, char value) { const auto ignored=write(fd,&value,1); (void)ignored; }
inline intptr_t tunnelArgument(int fd) { return fd; }
inline int readInput(char *buffer, size_t capacity, bool wait=false) {
    if (!wait) { pollfd input{STDIN_FILENO,POLLIN,0}; const auto ready=poll(&input,1,200); if(!ready || (ready<0 && errno==EINTR)) return 0; if(ready<0) return -1; }
    const auto size=read(STDIN_FILENO,buffer,capacity); return size>0 ? int(size) : -1;
}
inline bool writeOutput(const QByteArray &bytes) {
    size_t written=0;
    while(written<size_t(bytes.size())) {
        const auto count=write(STDOUT_FILENO,bytes.constData()+written,size_t(bytes.size())-written);
        if(count<0 && errno==EINTR) continue;
        if(count<=0) return false;
        written+=size_t(count);
    }
    return true;
}
inline QByteArray readSecret(const QString &target) {
    QProcess command; command.start("/usr/bin/secret-tool",{"lookup","application","GeekOCTunnel","profile",target});
    if(!command.waitForFinished(3000)) { command.kill(); command.waitForFinished(); return {}; }
    return command.exitCode()==0 ? command.readAllStandardOutput().trimmed() : QByteArray{};
}
inline bool writeSecret(const QString &target, const QByteArray &bytes) {
    if(bytes.size()>2560) return false;
    QProcess command; command.start("/usr/bin/secret-tool",{"store","--label=GeekOCTunnel","application","GeekOCTunnel","profile",target});
    if(!command.waitForStarted(3000)) return false;
    command.write(bytes); command.closeWriteChannel();
    if(!command.waitForFinished(10000)) { command.kill(); command.waitForFinished(); return false; }
    return command.exitStatus()==QProcess::NormalExit && command.exitCode()==0;
}
}
