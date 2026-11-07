#pragma once
#include "core/NetworkProtocol.h"
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/eventfd.h>
#include <poll.h>
#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <string>
#include <utility>

namespace geekoc::vpn {
struct PlatformError : std::runtime_error {
    int code;
    explicit PlatformError(const char *message, int value = errno) : std::runtime_error(message), code(value) {}
};
inline void check(bool ok, const char *message) { if (!ok) throw PlatformError(message); }
class Handle {
    int value_ = -1;
public:
    explicit Handle(int value = -1) : value_(value) {}
    ~Handle() { reset(); }
    Handle(const Handle &) = delete;
    Handle &operator=(const Handle &) = delete;
    Handle(Handle &&other) noexcept : value_(other.release()) {}
    Handle &operator=(Handle &&other) noexcept { reset(other.release()); return *this; }
    int get() const { return value_; }
    bool valid() const { return value_ >= 0; }
    int release() { return std::exchange(value_, -1); }
    void reset(int value = -1) { if (valid()) close(value_); value_ = value; }
};
inline bool uuid(const std::string &id) {
    if (id.size() != 36) return false;
    for (size_t i = 0; i < id.size(); ++i) {
        if (i == 8 || i == 13 || i == 18 || i == 23) { if (id[i] != '-') return false; }
        else if (!((id[i] >= '0' && id[i] <= '9') || (id[i] >= 'a' && id[i] <= 'f'))) return false;
    }
    return true;
}
inline sockaddr_un socketAddress(uid_t uid, pid_t parent, const std::string &id, socklen_t &length) {
    check(uuid(id), "invalid_session");
    const auto name = "geekoctunnel." + std::to_string(uid) + '.' + std::to_string(parent) + '.' + id;
    sockaddr_un address{}; address.sun_family = AF_UNIX;
    check(name.size() + 1 < sizeof(address.sun_path), "invalid_session");
    memcpy(address.sun_path + 1, name.data(), name.size());
    length = socklen_t(offsetof(sockaddr_un, sun_path) + 1 + name.size()); return address;
}
inline bool waitReadable(int fd, int cancel, int timeout) {
    pollfd items[2]{{fd,POLLIN,0},{cancel,POLLIN,0}};
    int result;
    do { result = poll(items, cancel >= 0 ? 2 : 1, timeout); } while (result < 0 && errno == EINTR);
    return result > 0 && !items[1].revents && (items[0].revents & POLLIN);
}
inline ucred peer(int fd) {
    ucred result{}; socklen_t size = sizeof(result);
    check(getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &result, &size) == 0 && size == sizeof(result), "helper_peer_mismatch");
    return result;
}
inline bool sendReply(int socket, const Reply &reply, int fd = -1) {
    iovec io{const_cast<Reply *>(&reply), sizeof(reply)};
    msghdr message{}; message.msg_iov = &io; message.msg_iovlen = 1;
    alignas(cmsghdr) char control[CMSG_SPACE(sizeof(int))]{};
    if (fd >= 0) {
        message.msg_control = control; message.msg_controllen = sizeof(control);
        auto *header = CMSG_FIRSTHDR(&message); header->cmsg_level = SOL_SOCKET; header->cmsg_type = SCM_RIGHTS; header->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(header), &fd, sizeof(fd));
    }
    return sendmsg(socket, &message, MSG_NOSIGNAL) == sizeof(reply);
}
}
