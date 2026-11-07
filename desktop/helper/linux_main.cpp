#include "platform/linux/PosixHandles.h"
#include <QCoreApplication>
#include <QFileInfo>
#include <QDir>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QHostAddress>
#include <QProcess>
#include <QProcessEnvironment>
#include <linux/if_tun.h>
#include <net/if.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/file.h>
#include <fcntl.h>
#include <csignal>

using namespace geekoc::vpn;

static QByteArray run(const QString &binary, const QStringList &args) {
    QProcess command;
    QProcessEnvironment environment; environment.insert("PATH","/usr/sbin:/usr/bin"); environment.insert("LANG","C");
    command.setProcessEnvironment(environment); command.start(binary,args);
    if(!command.waitForFinished(5000)) { command.kill(); command.waitForFinished(); throw PlatformError("network_command_timeout",ETIMEDOUT); }
    if(command.exitStatus()!=QProcess::NormalExit || command.exitCode()!=0) {
        throw PlatformError("network_command_failed",EIO);
    }
    return command.readAllStandardOutput();
}
static void trustedPath(QString path) {
    while(!path.isEmpty()) {
        struct stat status{};
        check(lstat(path.toUtf8().constData(),&status)==0 && status.st_uid==0 && !(status.st_mode & 0022) && !S_ISLNK(status.st_mode),"unsafe_installation");
        if(path=="/") break;
        path=QFileInfo(path).absolutePath();
    }
}
static void rejectConflicts(const Plan &plan) {
    const auto document=QJsonDocument::fromJson(run("/usr/sbin/ip",{"-j","-4","route","show","table","all"}));
    check(document.isArray(),"route_read_failed");
    for(const auto &entry:document.array()) {
        const auto destination=entry.toObject().value("dst").toString();
        if(destination=="default" || destination.isEmpty()) continue;
        const auto parts=destination.split('/'); bool ok=false;
        const auto network=QHostAddress(parts[0]).toIPv4Address(&ok); check(ok,"route_read_failed");
        const auto prefix=parts.size()==1 ? 32u : parts[1].toUInt(&ok); check(ok && prefix<=32,"route_read_failed");
        const Route existing{network & mask(prefix),prefix};
        for(uint32_t i=0;i<plan.routeCount;++i)
            if(contains(existing,plan.routes[i].network) || contains(plan.routes[i],existing.network)) throw PlatformError("route_conflict",EEXIST);
    }
}

int main(int argc,char **argv) {
    QCoreApplication app(argc,argv); signal(SIGPIPE,SIG_IGN);
    Handle control,tun; Reply reply; QString name; bool created=false, dnsConfigured=false;
    try {
        check(geteuid()==0 && argc==5 && std::string(argv[1])=="--parent" && std::string(argv[3])=="--session","invalid_arguments");
        bool ok=false; const auto parent=QString::fromLatin1(argv[2]).toInt(&ok);
        check(ok && parent>1 && uuid(argv[4]),"invalid_parent");
        struct stat status{}; const auto proc=QString("/proc/%1").arg(parent);
        check(stat(proc.toUtf8().constData(),&status)==0 && status.st_uid!=0,"parent_user_mismatch");
        const auto executable=QFileInfo(proc+"/exe").symLinkTarget();
        check(executable==QCoreApplication::applicationDirPath()+"/geekoctunnel-core","parent_identity_failed");
        trustedPath(executable); trustedPath(QCoreApplication::applicationFilePath());
        const auto invokingUid=qEnvironmentVariable("PKEXEC_UID");
        check(!invokingUid.isEmpty() && invokingUid.toUInt(&ok)==status.st_uid && ok,"parent_user_mismatch");
        control.reset(socket(AF_UNIX,SOCK_SEQPACKET|SOCK_CLOEXEC,0)); check(control.valid(),"pipe_create_failed");
        socklen_t length=0; const auto address=socketAddress(status.st_uid,parent,argv[4],length);
        check(connect(control.get(),reinterpret_cast<const sockaddr *>(&address),length)==0,"pipe_open_failed");
        const auto credentials=peer(control.get()); check(credentials.pid==parent && credentials.uid==status.st_uid,"pipe_peer_mismatch");
        Plan plan{};
        check(waitReadable(control.get(),-1,5000) && recv(control.get(),&plan,sizeof(plan),MSG_TRUNC)==sizeof(plan) && valid(plan),"invalid_network_plan");
        Handle setupLock(open("/run/lock/geekoctunnel-network.lock",O_CREAT|O_RDWR|O_CLOEXEC|O_NOFOLLOW,0600));
        struct stat lockInfo{};
        check(setupLock.valid() && fstat(setupLock.get(),&lockInfo)==0 && lockInfo.st_uid==0 && S_ISREG(lockInfo.st_mode) && !(lockInfo.st_mode & 0077),"network_lock_failed");
        bool locked=false;
        for(int i=0;i<400 && !locked;++i) { locked=flock(setupLock.get(),LOCK_EX|LOCK_NB)==0; if(!locked) usleep(50000); }
        check(locked,"network_setup_busy");
        rejectConflicts(plan);
        name="goc"+QString::fromLatin1(argv[4]).remove('-').left(11);
        check(if_nametoindex(name.toUtf8().constData())==0,"adapter_already_exists");
        tun.reset(open("/dev/net/tun",O_RDWR|O_CLOEXEC|O_NONBLOCK)); check(tun.valid(),"tun_open_failed");
        ifreq request{}; request.ifr_flags=IFF_TUN|IFF_NO_PI;
        const auto encoded=name.toUtf8(); memcpy(request.ifr_name,encoded.constData(),size_t(encoded.size()));
        check(ioctl(tun.get(),TUNSETIFF,&request)==0,"tun_create_failed"); created=true;
        const auto index=if_nametoindex(encoded.constData()); check(index!=0,"interface_not_found"); reply.luid=index;
        run("/usr/sbin/ip",{"address","add",QHostAddress(plan.address).toString()+"/32","dev",name});
        run("/usr/sbin/ip",{"link","set","dev",name,"mtu",QString::number(plan.mtu),"up"});
        for(uint32_t i=0;i<plan.routeCount;++i)
            run("/usr/sbin/ip",{"route","add",QHostAddress(plan.routes[i].network).toString()+"/"+QString::number(plan.routes[i].prefix),"dev",name,"metric","50"});
        if(plan.dnsCount) {
            QStringList servers{"dns",name}; for(uint32_t i=0;i<plan.dnsCount;++i) servers.append(QHostAddress(plan.dns[i]).toString());
            run("/usr/bin/resolvectl",servers); dnsConfigured=true;
            run("/usr/bin/resolvectl",{"domain",name,"~."});
            run("/usr/bin/resolvectl",{"default-route",name,"yes"});
        }
        setupLock.reset();
        reply.phase=1;
        check(sendReply(control.get(),reply,tun.get()),"ready_reply_failed");
        // The private control socket closes when Core exits, including crashes; no persistent TUN is created.
        pollfd descriptor{control.get(),POLLIN,0};
        while(true) {
            const auto available=poll(&descriptor,1,1000);
            if(available>0) { char stop; recv(control.get(),&stop,1,MSG_DONTWAIT); break; }
            if((available<0 && errno!=EINTR) || kill(parent,0)<0) break;
        }
        reply.phase=2;
    } catch(const PlatformError &error) {
        reply.error=error.code ? uint32_t(error.code) : uint32_t(EIO);
        std::fprintf(stderr,"%s\n",error.what());
    }
      catch(...) { reply.error=EIO; }
    bool clean=true;
    if(created) {
        if(dnsConfigured) { try { run("/usr/bin/resolvectl",{"revert",name}); } catch(...) { /* Removing the link also removes its resolved configuration. */ } }
        tun.reset();
        // An injected TUN fd is caller-owned. Wait for Core to close its copy too.
        for(int i=0;i<100 && if_nametoindex(name.toUtf8().constData())==reply.luid;++i) usleep(50000);
        clean=if_nametoindex(name.toUtf8().constData())!=reply.luid;
    }
    if(!clean) reply.error=EIO;
    if(control.valid()) sendReply(control.get(),reply);
    return clean ? 0 : 2;
}
