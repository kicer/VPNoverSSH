package dns

import (
	D "github.com/miekg/dns"

	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"github.com/xjasonlyu/tun2socks/v2/log"
)

// Intercept answers a DNS query that arrived through the TUN device using
// the fake-ip pool, without forwarding it to the proxy.
//
// This replaces the system-stack UDP/53 server introduced by PR #374, which
// cannot work inside an Android app: binding a privileged port (<1024) in
// the kernel requires CAP_NET_BIND_SERVICE, which app processes do not have,
// so net.ListenUDP("...:53") always fails with EACCES. Answering inside the
// gVisor stack is merely matching destination ports on packets already read
// from the tun fd, so no privileges are involved.
//
// Returns true when the datagram was consumed (answered, or dropped because
// it is not a plain query) and the session must not be handed to the proxy.
func Intercept(uc adapter.UDPConn) bool {
	if !fakeDNSenabled || fakePool == nil {
		return false
	}

	buf := make([]byte, 64*1024)
	n, err := uc.Read(buf)
	if err != nil {
		return true
	}

	var query D.Msg
	if err := query.Unpack(buf[:n]); err != nil || query.Response || len(query.Question) == 0 {
		log.Debugf("[DNS] drop datagram on port 53: %v", err)
		return true
	}

	reply, err := fakeipHandler(fakePool)(&query)
	if err != nil {
		log.Debugf("[DNS] fake response for %s: %v", query.Question[0].Name, err)
		return true
	}
	reply.Compress = true

	out, err := reply.Pack()
	if err != nil {
		log.Debugf("[DNS] pack response: %v", err)
		return true
	}

	if _, err := uc.Write(out); err != nil {
		log.Debugf("[DNS] write response: %v", err)
		return true
	}

	log.Infof("[DNS] answered %s %s from fake-ip pool",
		D.Type(query.Question[0].Qtype), query.Question[0].Name)
	return true
}
