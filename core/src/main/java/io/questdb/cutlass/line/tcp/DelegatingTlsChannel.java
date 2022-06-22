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

package io.questdb.cutlass.line.tcp;

import io.questdb.cutlass.line.LineChannel;
import io.questdb.cutlass.line.LineSenderException;
import io.questdb.cutlass.line.Sender;
import io.questdb.std.MemoryTag;
import io.questdb.std.Misc;
import io.questdb.std.Unsafe;
import io.questdb.std.Vect;

import javax.net.ssl.*;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

public final class DelegatingTlsChannel implements LineChannel {
    private static final int INITIAL_BUFFER_CAPACITY = 64 * 1024;
    private static final long ADDRESS_FIELD_OFFSET;
    private static final long LIMIT_FIELD_OFFSET;
    private static final long CAPACITY_FIELD_OFFSET;

    private static final int INITIAL_STATE = 0;
    private static final int AFTER_HANDSHAKE = 1;
    private static final int CLOSING = 2;
    private static final int CLOSED = 3;

    private static final TrustManager[] BLIND_TRUST_MANAGERS = new TrustManager[]{new X509TrustManager() {
        public void checkClientTrusted(X509Certificate[] certs, String t) {
        }
        public void checkServerTrusted(X509Certificate[] certs, String t) {
        }
        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }
    }};


    private final LineChannel delegate;
    private final SSLEngine sslEngine;

    private final ByteBuffer wrapInputBuffer;
    private final ByteBuffer wrapOutputBuffer;
    private final ByteBuffer unwrapInputBuffer;
    private final ByteBuffer unwrapOutputBuffer;
    private final ByteBuffer dummyBuffer;

    private long wrapOutputBufferPtr;
    private long unwrapInputBufferPtr;
    private long unwrapOutputBufferPtr;

    private int state = INITIAL_STATE;

    static {
        Field addressField;
        Field limitField;
        Field capacityField;
        try {
            addressField = Buffer.class.getDeclaredField("address");
            limitField = Buffer.class.getDeclaredField("limit");
            capacityField = Buffer.class.getDeclaredField("capacity");
        } catch (NoSuchFieldException e) {
            // possible improvement: implement a fallback strategy when reflection is unavailable for any reason.
            throw new LineSenderException("unexpected buffer");
        }
        ADDRESS_FIELD_OFFSET = Unsafe.getUnsafe().objectFieldOffset(addressField);
        LIMIT_FIELD_OFFSET = Unsafe.getUnsafe().objectFieldOffset(limitField);
        CAPACITY_FIELD_OFFSET = Unsafe.getUnsafe().objectFieldOffset(capacityField);
    }

    public DelegatingTlsChannel(LineChannel delegate, String trustStorePath, char[] password, Sender.TlsValidationMode validationMode) {
        this.delegate = delegate;
        this.sslEngine = createSslEngine(trustStorePath, password, validationMode);

        // wrapInputBuffer is just a placeholder, we set the internal address, capacity and limit in send()
        this.wrapInputBuffer = ByteBuffer.allocateDirect(0);

        // allows to override in tests, but we don't necessary want to expose this to users.
        int initialCapacity = Integer.getInteger("questdb.experimental.tls.buffersize", INITIAL_BUFFER_CAPACITY);

        // we want to track allocated memory hence we just create dummy direct byte buffers
        // and later reset it to manually allocated memory
        this.wrapOutputBuffer = ByteBuffer.allocateDirect(0);
        this.unwrapInputBuffer = ByteBuffer.allocateDirect(0);
        this.unwrapOutputBuffer = ByteBuffer.allocateDirect(0);

        this.wrapOutputBufferPtr = allocateMemoryAndResetBuffer(wrapOutputBuffer, initialCapacity);
        this.unwrapInputBufferPtr = allocateMemoryAndResetBuffer(unwrapInputBuffer, initialCapacity);
        this.unwrapOutputBufferPtr = allocateMemoryAndResetBuffer(unwrapOutputBuffer, initialCapacity);

        this.dummyBuffer = ByteBuffer.allocate(0);

        try {
            handshakeLoop();
        } catch (SSLException e) {
            throw new LineSenderException("error during TLS handshake", e);
        }
    }

    private static SSLEngine createSslEngine(String trustStorePath, char[] trustStorePassword, Sender.TlsValidationMode validationMode) {
        assert trustStorePath == null || validationMode == Sender.TlsValidationMode.DEFAULT;
        try {
            SSLContext sslContext;
            // intentionally not exposed to end user as an option
            // it's used for testing, but dangerous in prod
            if (trustStorePath != null) {
                sslContext = SSLContext.getInstance("TLS");
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                KeyStore jks = KeyStore.getInstance("JKS");

                InputStream trustStoreStream = null;
                try {
                    if (trustStorePath.startsWith("classpath:")) {
                        String adjustedPath = trustStorePath.substring("classpath:".length());
                        trustStoreStream = DelegatingTlsChannel.class.getResourceAsStream(adjustedPath);
                        if (trustStoreStream == null) {
                            throw new LineSenderException("Configured trust at classpath:" + trustStorePath + " is unavailable on a classpath");
                        }
                    } else {
                        trustStoreStream = new BufferedInputStream(new FileInputStream(trustStorePath));
                    }
                    jks.load(trustStoreStream, trustStorePassword);
                } finally {
                    if (trustStoreStream != null) {
                        trustStoreStream.close();
                    }
                }
                tmf.init(jks);
                TrustManager[] trustManagers = tmf.getTrustManagers();
                sslContext.init(null, trustManagers, new SecureRandom());
            } else if (validationMode == Sender.TlsValidationMode.INSECURE) {
                sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, BLIND_TRUST_MANAGERS, new SecureRandom());
            } else {
                sslContext = SSLContext.getDefault();
            }
            SSLEngine sslEngine = sslContext.createSSLEngine();
            sslEngine.setUseClientMode(true);
            return sslEngine;
        } catch (Throwable t) {
            if (t instanceof LineSenderException) {
                throw (LineSenderException)t;
            }
            throw new LineSenderException("error while creating openssl engine", t);
        }
    }

    @Override
    public void send(long ptr, int len) {
        try {
            resetBufferToPointer(wrapInputBuffer, ptr, len);
            wrapInputBuffer.position(0);
            wrapLoop(wrapInputBuffer);
            assert !wrapInputBuffer.hasRemaining();
        } catch (SSLException e) {
            throw new LineSenderException("error while sending data to questdb server", e);
        }
    }

    private void handshakeLoop() throws SSLException {
        if (state != INITIAL_STATE) {
            return;
        }

        // trigger handshaking - otherwise the initial state is NOT_HANDSHAKING
        sslEngine.beginHandshake();
        for (;;) {
            SSLEngineResult.HandshakeStatus status = sslEngine.getHandshakeStatus();
            switch (status) {
                case NOT_HANDSHAKING:
                    state = AFTER_HANDSHAKE;
                    return;
                case NEED_TASK:
                    sslEngine.getDelegatedTask().run();
                    break;
                case NEED_WRAP:
                    wrapLoop(dummyBuffer);
                    break;
                case NEED_UNWRAP:
                    unwrapLoop();
                    break;
                case FINISHED:
                    throw new LineSenderException("getHandshakeStatus() returns FINISHED. It should not be possible.");
                default:
                    throw new LineSenderException(status + "not supported");
            }
        }
    }

    private void wrapLoop(ByteBuffer src) throws SSLException {
        do {
            SSLEngineResult result = sslEngine.wrap(src, wrapOutputBuffer);
            switch (result.getStatus()) {
                case BUFFER_UNDERFLOW:
                    throw new LineSenderException("should not happen");
                case BUFFER_OVERFLOW:
                    growWrapOutputBuffer();
                    break;
                case OK:
                    writeToUpstreamAndClear();
                    break;
                case CLOSED:
                    if (state != CLOSING) {
                        throw new LineSenderException("connection closed");
                    }
                    return;
            }
        } while (src.hasRemaining());
    }

    private void unwrapLoop() throws SSLException {
        // we want the loop to return as soon as we have some unwrapped data in the output buffer
        while (unwrapOutputBuffer.position() == 0) {
            readFromUpstream(false);
            unwrapInputBuffer.flip();
            SSLEngineResult result = sslEngine.unwrap(unwrapInputBuffer, unwrapOutputBuffer);
            unwrapInputBuffer.compact();
            switch (result.getStatus()) {
                case BUFFER_UNDERFLOW:
                    // we need more input no matter what. so let's force reading from the upstream channel
                    readFromUpstream(true);
                    break;
                case BUFFER_OVERFLOW:
                    if (unwrapOutputBuffer.position() != 0) {
                        // we have at least something, that's enough
                        // if it's not enough then it's up to the caller to call us again
                        return;
                    }

                    // there was overflow and we have nothing
                    // apparently the output buffer cannot fit even a single TLS record. let's grow it!
                    growUnwrapOutputBuffer();
                    break;
                case OK:
                    return;
                case CLOSED:
                    throw new LineSenderException("connection closed");
            }
        }
    }

    private void writeToUpstreamAndClear() {
        assert wrapOutputBuffer.limit() == wrapOutputBuffer.capacity();

        // we don't flip the wrapOutputBuffer before reading from it
        // hence the writer position is the actual length to be sent to the upstream channel
        int len = wrapOutputBuffer.position();

        assert Unsafe.getUnsafe().getLong(wrapOutputBuffer, ADDRESS_FIELD_OFFSET) == wrapOutputBufferPtr;
        delegate.send(wrapOutputBufferPtr, len);

        // we know limit == capacity
        // thus setting the position to 0 is equivalent to clearing
        wrapOutputBuffer.position(0);
    }

    private void readFromUpstream(boolean force) {
        if (unwrapInputBuffer.position() != 0 && !force) {
            // we don't want to block on receive() if there are still data to be processed
            // unless we are forced to do so
            return;
        }

        assert unwrapInputBuffer.limit() == unwrapInputBuffer.capacity();
        int remainingLen = unwrapInputBuffer.remaining();
        if (remainingLen == 0) {
            growUnwrapInputBuffer();
            remainingLen = unwrapInputBuffer.remaining();
        }
        assert Unsafe.getUnsafe().getLong(unwrapInputBuffer, ADDRESS_FIELD_OFFSET) == unwrapInputBufferPtr;
        long adjustedPtr = unwrapInputBufferPtr + unwrapInputBuffer.position();

        int receive = delegate.receive(adjustedPtr, remainingLen);
        if (receive < 0) {
            throw new LineSenderException("connection closed");
        }
        unwrapInputBuffer.position(unwrapInputBuffer.position() + receive);
    }

    @Override
    public int receive(long ptr, int len) {
        try {
            unwrapLoop();
            unwrapOutputBuffer.flip();
            int i = unwrapOutputBufferToPtr(ptr, len);
            unwrapOutputBuffer.compact();
            return i;
        } catch (SSLException e) {
            throw new LineSenderException("error while receiving data from questdb server", e);
        }
    }

    private int unwrapOutputBufferToPtr(long dstPtr, int dstLen) {
        int oldPosition = unwrapOutputBuffer.position();

        assert Unsafe.getUnsafe().getLong(unwrapOutputBufferPtr, ADDRESS_FIELD_OFFSET) == unwrapOutputBufferPtr;
        long srcPtr = unwrapOutputBufferPtr + oldPosition;
        int srcLen = unwrapOutputBuffer.remaining();
        int len = Math.min(dstLen, srcLen);
        Vect.memcpy(dstPtr, srcPtr, len);
        unwrapOutputBuffer.position(oldPosition + len);
        return len;
    }

    private void growWrapOutputBuffer() {
        wrapOutputBufferPtr = expandBuffer(wrapOutputBuffer, wrapOutputBufferPtr);
    }

    private void growUnwrapOutputBuffer() {
        unwrapOutputBufferPtr = expandBuffer(unwrapOutputBuffer, unwrapOutputBufferPtr);
    }

    private void growUnwrapInputBuffer() {
        unwrapInputBufferPtr = expandBuffer(unwrapInputBuffer, unwrapInputBufferPtr);
    }

    private static long expandBuffer(ByteBuffer buffer, long oldAddress) {
        int oldCapacity = buffer.capacity();
        int newCapacity = oldCapacity * 2;
        long newAddress = Unsafe.realloc(oldAddress, oldCapacity, newCapacity, MemoryTag.NATIVE_DEFAULT);
        resetBufferToPointer(buffer, newAddress, newCapacity);
        return newAddress;
    }

    private static long allocateMemoryAndResetBuffer(ByteBuffer buffer, int capacity) {
        long newAddress = Unsafe.malloc(capacity, MemoryTag.NATIVE_DEFAULT);
        resetBufferToPointer(buffer, newAddress, capacity);
        return newAddress;
    }

    private static void resetBufferToPointer(ByteBuffer buffer, long ptr, int len) {
        assert buffer.isDirect();
        Unsafe.getUnsafe().putLong(buffer, ADDRESS_FIELD_OFFSET, ptr);
        Unsafe.getUnsafe().putLong(buffer, LIMIT_FIELD_OFFSET, len);
        Unsafe.getUnsafe().putLong(buffer, CAPACITY_FIELD_OFFSET, len);
    }

    @Override
    public int errno() {
        return delegate.errno();
    }

    @Override
    public void close() throws IOException {
        int prevState = state;
        state = CLOSING;
        if (prevState == AFTER_HANDSHAKE) {
            sslEngine.closeOutbound();
            wrapLoop(dummyBuffer);
            try {
                writeToUpstreamAndClear();
            } catch (LineSenderException e) {
                // best effort TLS close signal
            }
        }
        state = CLOSED;
        Misc.free(delegate);
        Unsafe.free(wrapOutputBufferPtr, wrapOutputBuffer.capacity(), MemoryTag.NATIVE_DEFAULT);
        Unsafe.free(unwrapInputBufferPtr, unwrapInputBuffer.capacity(), MemoryTag.NATIVE_DEFAULT);
        Unsafe.free(unwrapOutputBufferPtr, unwrapOutputBuffer.capacity(), MemoryTag.NATIVE_DEFAULT);
    }
}
