/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cutlass.line;

import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.network.NetworkError;
import io.questdb.network.NetworkFacade;

public final class UdpLineChannel implements LineChannel {
    private static final Log LOG = LogFactory.getLog(PlanTcpLineChannel.class);

    private final NetworkFacade nf;
    private final long fd;
    private final long sockaddr;

    UdpLineChannel(NetworkFacade nf, int interfaceIPv4Address, int sendToAddress, int port, int ttl) throws NetworkError {
        this.nf = nf;
        this.fd = nf.socketUdp();
        this.sockaddr = nf.sockaddr(sendToAddress, port);

        if (fd == -1) {
            throw NetworkError.instance(nf.errno()).put("could not create UDP socket");
        }

        if (nf.setMulticastInterface(fd, interfaceIPv4Address) != 0) {
            final int errno = nf.errno();
            nf.close(fd, LOG);
            throw NetworkError.instance(errno).put("could not bind to ").ip(interfaceIPv4Address);
        }

        if (nf.setMulticastTtl(fd, ttl) != 0) {
            final int errno = nf.errno();
            nf.close(fd, LOG);
            throw NetworkError.instance(errno).put("could not set ttl [fd=").put(fd).put(", ttl=").put(ttl).put(']');
        }
    }

    @Override
    public void close() {
        if (nf.close(fd) != 0) {
            LOG.error().$("could not close network socket [fd=").$(fd).$(", errno=").$(nf.errno()).$(']').$();
        }
        nf.freeSockAddr(sockaddr);
    }

    @Override
    public void send(long ptr, int len) {
        if (nf.sendTo(fd, ptr, len, sockaddr) != len) {
            throw NetworkError.instance(nf.errno()).put("send error");
        }
    }

    @Override
    public int receive(long ptr, int len) {
        throw new UnsupportedOperationException("Udp channel does not support receive()");
    }

    @Override
    public int errno() {
        return nf.errno();
    }
}
