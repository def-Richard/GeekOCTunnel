#pragma once
#include "PosixHandles.h"
#include <QCoreApplication>
#include <QProcess>
#include <mutex>
#include <atomic>

namespace geekoc::vpn {
class HelperSession {
    Handle control_;
    QProcess process_;
    std::mutex mutex_;
    std::atomic_bool started_{false};
    bool stopRequested_ = false;
public:
    ~HelperSession() { stop(); }
    bool transportLost() {
        std::lock_guard guard(mutex_);
        if(!started_ || stopRequested_ || !control_.valid()) return false;
        pollfd fd{control_.get(),POLLIN,0};
        return poll(&fd,1,0)>0 && (fd.revents & (POLLHUP|POLLERR|POLLNVAL));
    }
    int start(const std::string &id, const Plan &plan, int cancelled) {
        { std::lock_guard guard(mutex_); stopRequested_ = false; }
        check(valid(plan), "invalid_network_plan");
        Handle listener(socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0)); check(listener.valid(), "pipe_create_failed");
        socklen_t length = 0; const auto address = socketAddress(getuid(), getpid(), id, length);
        check(bind(listener.get(), reinterpret_cast<const sockaddr *>(&address), length) == 0 && listen(listener.get(), 1) == 0, "pipe_create_failed");
        process_.setStandardOutputFile(QProcess::nullDevice());
        process_.start("/usr/bin/pkexec", {QCoreApplication::applicationDirPath() + "/geekoctunnel-helper", "--parent", QString::number(getpid()), "--session", QString::fromStdString(id)});
        check(process_.waitForStarted(5000), "helper_launch_failed");
        bool accepted=false;
        for(int i=0;i<120 && !accepted;++i) {
            accepted=waitReadable(listener.get(),cancelled,500);
            if(accepted) break;
            pollfd stop{cancelled,POLLIN,0};
            if(poll(&stop,1,0)>0) throw PlatformError("cancelled",ECANCELED);
            if(process_.waitForFinished(0)) {
                const auto detail=process_.readAllStandardError().trimmed();
                for(const auto *code:{"parent_identity_failed","parent_user_mismatch","unsafe_installation","invalid_parent","pipe_open_failed","pipe_peer_mismatch"})
                    if(detail==code) throw PlatformError(code,EACCES);
                throw PlatformError("permission_denied",EACCES);
            }
        }
        check(accepted,"permission_denied");
        Handle connection(accept4(listener.get(), nullptr, nullptr, SOCK_CLOEXEC)); check(connection.valid(), "helper_connect_failed");
        const auto credentials = peer(connection.get());
        check(credentials.pid == process_.processId() && credentials.uid == 0, "helper_peer_mismatch");
        { std::lock_guard guard(mutex_); control_ = std::move(connection); }
        check(send(control_.get(), &plan, sizeof(plan), MSG_NOSIGNAL) == sizeof(plan), "helper_plan_failed");
        check(waitReadable(control_.get(), cancelled, 30000), "helper_setup_failed");
        Reply reply{}; iovec io{&reply,sizeof(reply)};
        alignas(cmsghdr) char ancillary[CMSG_SPACE(sizeof(int))]{};
        msghdr message{}; message.msg_iov=&io; message.msg_iovlen=1; message.msg_control=ancillary; message.msg_controllen=sizeof(ancillary);
        const auto count=recvmsg(control_.get(), &message, MSG_CMSG_CLOEXEC);
        Handle tun;
        if (auto *header=CMSG_FIRSTHDR(&message); header && header->cmsg_level==SOL_SOCKET && header->cmsg_type==SCM_RIGHTS && header->cmsg_len==CMSG_LEN(sizeof(int))) {
            int fd=-1; memcpy(&fd,CMSG_DATA(header),sizeof(fd)); tun.reset(fd);
        }
        check(count==sizeof(reply) && !(message.msg_flags & (MSG_TRUNC|MSG_CTRUNC)) && reply.magic==Magic, "invalid_helper_reply");
        if (reply.error) throw PlatformError(reply.error==EEXIST ? "route_conflict" : "network_setup_failed", int(reply.error));
        check(reply.phase==1 && tun.valid(), "tunnel_transport_failed");
        started_=true; return tun.release();
    }
    void requestStop() {
        std::lock_guard guard(mutex_);
        if(control_.valid() && !stopRequested_) {
            const char stop=1;
            stopRequested_ = send(control_.get(),&stop,1,MSG_NOSIGNAL|MSG_DONTWAIT)==1;
        }
    }
    bool stop() {
        requestStop(); std::lock_guard guard(mutex_); bool clean=true;
        if (started_) {
            Reply reply{};
            const bool readable=waitReadable(control_.get(),-1,10000);
            const auto received=readable ? recv(control_.get(),&reply,sizeof(reply),0) : -1;
            clean=received==sizeof(reply)
                && reply.magic==Magic && reply.phase==2 && reply.error==0;
        }
        control_.reset();
        if (process_.state()!=QProcess::NotRunning && !process_.waitForFinished(10000)) { process_.kill(); process_.waitForFinished(3000); clean=false; }
        if (started_) clean &= process_.exitStatus()==QProcess::NormalExit && process_.exitCode()==0;
        started_=false; return clean;
    }
};
}
