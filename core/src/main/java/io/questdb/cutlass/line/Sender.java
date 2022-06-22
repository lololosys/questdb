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

import io.questdb.cutlass.line.tcp.AuthDb;
import io.questdb.cutlass.line.tcp.DelegatingTlsChannel;
import io.questdb.cutlass.line.tcp.PlainTcpLineChannel;
import io.questdb.network.NetworkFacade;
import io.questdb.network.NetworkFacadeImpl;
import io.questdb.std.Chars;
import io.questdb.std.Numbers;
import io.questdb.std.NumericException;

import javax.security.auth.DestroyFailedException;
import java.io.Closeable;
import java.io.IOException;
import java.security.PrivateKey;

/**
 * ILP client to feed data to a remote QuestDB instance.
 *
 * Use {@link #builder()} method to create a new instance.
 * <br>
 * How to use the Sender:
 * <ol>
 *     <li>Obtain an instance via {@link #builder()}</li>
 *     <li>Use {@link #table(CharSequence)} to select a table</li>
 *     <li>Use {@link #symbol(CharSequence, CharSequence)} to add all symbols. You must add symbols before adding other columns.</li>
 *     <li>Use {@link #stringColumn(CharSequence, CharSequence)}, {@link #longColumn(CharSequence, long)}, {@link #doubleColumn(CharSequence, double)},
 *     {@link #boolColumn(CharSequence, boolean)} to add remaining columns columns</li>
 *     <li>Use {@link #at(long)} to finish a row with an explicit timestamp.Alternatively, you can use use
 *     {@link #atNow()} which will add a timestamp on a server.</li>
 *     <li>Optionally: You can use {@link #flush()} to send locally buffered data into a server</li>
 * </ol>
 * <br>
 *
 * Sender implements the <code>java.io.Closeable</code> interface. Thus, you must call the {@link #close()} method
 * when you no longer need it.
 * <br>
 * Thread-safety: Sender is not thread-safe. Each thread-safe needs its own instance or you have to implement
 * a mechanism for passing Sender instances among thread. An object pool could have this role.
 * <br>
 * Error-handling: Most errors throw an instance of {@link LineSenderException}.
 *
 */
public interface Sender extends Closeable {

    /**
     * Select the table for a new row. This is always the first method to start a error. It's an error to call other
     * methods without calling this method first.
     *
     * @param table name of the table
     * @return this instance for method chaining
     */
    Sender table(CharSequence table);

    /**
     * Add a column with an integer value.
     *
     * @param name name of the column
     * @param value value to add
     * @return this instance for method chaining
     */
    Sender longColumn(CharSequence name, long value);

    /**
     * Add a column with a string value
     * @param name name of the column
     * @param value value to add
     * @return this instance for method chaining
     */
    Sender stringColumn(CharSequence name, CharSequence value);

    /**
     * Add a column with a floating point value
     * @param name name of the column
     * @param value value to add
     * @return this instance for method chaining
     */
    Sender doubleColumn(CharSequence name, double value);

    /**
     * Add a column with a boolean value
     * @param name name of the column
     * @param value value to add
     * @return this instance for method chaining
     */
    Sender boolColumn(CharSequence name, boolean value);

    /**
     * Add a column with a symbol value. You must call add symbols before adding any other column types
     *
     * @param name name of the column
     * @param value value to add
     * @return this instance for method chaining
     */
    Sender symbol(CharSequence name, CharSequence value);

    /**
     * Finalize the current row and let QuestDB server to assign a timestamp. If you need to set timestamp
     * explicitly then see {@link #at(long)}
     * <br>
     * After calling this method you can start a new row by calling {@link #table(CharSequence)} again.
     *
     */
    void atNow();

    /**
     *  Finalize the current row and assign an explicit timestamp in Epoch nanoseconds.
     *  After calling this method you can start a new row by calling {@link #table(CharSequence)} again.
     *
     * @param timestamp timestamp in Epoch nanoseconds.
     */
    void at(long timestamp);

    /**
     * Force sending internal buffers to a server.
     * <br>
     * This is an optional method, it's not strictly necessary to call it. Normally, messages are accumulated in
     * internal buffers and Sender itself is flushing them automatically as it sees fit. This method is useful when you
     * need a fine control over this behaviour.
     *
     */
    void flush();

    /**
     * Construct a Builder object to create a new Sender instance.
     *
     * @return Builder object to create a new Sender instance.
     */
    static LineSenderBuilder builder() {
        return new LineSenderBuilder();
    }

    /**
     * Builder class to construct a new instance of a Sender.
     * <br>
     * Example usage:
     * <pre>{@code
     * try (Sender sender = Sender.builder()
     *  .address("localhost:9001")
     *  .build()) {
     *      sender.table(tableName).column("value", 42).atNow();
     *  }
     * }</pre>
     *
     */
    final class LineSenderBuilder {
        // indicates buffer capacity was not set explicitly
        private static final byte BUFFER_CAPACITY_DEFAULT = 0;
        // indicate port was not set explicitly
        private static final byte PORT_DEFAULT = 0;

        private static final int DEFAULT_BUFFER_CAPACITY = 64 * 1024;
        private static final int DEFAULT_PORT = 9001;

        private static final int MIN_BUFFER_SIZE_FOR_AUTH = 512 + 1; // challenge size + 1;

        private int port = PORT_DEFAULT;
        private CharSequence address;
        private int addressLimit;
        private PrivateKey privateKey;
        private boolean shouldDestroyPrivKey;
        private int bufferCapacity = BUFFER_CAPACITY_DEFAULT;
        private boolean tlsEnabled;
        private String trustStorePath;
        private String keyId;
        private char[] trustStorePassword;
        private TlsValidationMode tlsValidationMode = TlsValidationMode.DEFAULT;

        private LineSenderBuilder() {

        }


        /**
         * Set address of a QuestDB server. It can be either a domain name or a textual representation of an IP address.
         * Only IPv4 addresses are supported.
         * <br>
         * Optionally, you can also include a port. In this can you separate a port from the address by using a colon.
         * Example: my.example.org:54321.
         *
         * If you can include a port then you must not call {@link LineSenderBuilder#port(int)}.
         *
         * @param address address of a QuestDB server
         * @return this instance for method chaining.
         */
        public LineSenderBuilder address(CharSequence address) {
            if (this.address != null) {
                throw new LineSenderException("server address is already configured to " + this.address);
            }
            if (address.length() == 0) {
                throw new LineSenderException("address cannot be empty");
            }
            int portIndex = Chars.indexOf(address, ':');
            if (portIndex + 1 == address.length()) {
                throw new LineSenderException("cannot parse address " + address + ". address cannot ends with :");
            }
            if (portIndex != -1) {
                if (port != 0) {
                    throw new LineSenderException("address " + address + " contains a port, but a port was already configured to " + port);
                }
                addressLimit = portIndex;
                try {
                    port = Numbers.parseInt(address, portIndex + 1, address.length());
                } catch (NumericException e) {
                    throw new LineSenderException("cannot parse port from address " + address, e);
                }
            } else {
                addressLimit = address.length();
            }
            this.address = address;
            return this;
        }

        /**
         * Set port where a QuestDB server is listening on.
         *
         * @param port port where a QuestDB server is listening on.
         * @return this instance for method chaining
         */
        public LineSenderBuilder port(int port) {
            if (this.port != 0) {
                throw new LineSenderException("post is already configured to " + this.port);
            }
            this.port = port;
            return this;
        }

        /**
         * Configure authentication. This is needed when QuestDB server required clients to authenticate.
         *
         * @param keyId keyId the client will send to a server.
         * @return an instance of {@link AuthBuilder}. As to finish authentication configuration.
         */
        public LineSenderBuilder.AuthBuilder enableAuth(String keyId) {
            if (this.keyId != null) {
                throw new LineSenderException("authentication keyId was already configured");
            }
            this.keyId = keyId;
            return new LineSenderBuilder.AuthBuilder();
        }

        /**
         * Instruct a client to use TLS when connecting to a QuestDB server
         *
         * @return this instance for method chaining.
         */
        public LineSenderBuilder enableTls() {
            if (tlsEnabled) {
                throw new LineSenderException("tls was already configured");
            }
            tlsEnabled = true;
            return this;
        }

        /**
         * Configure capacity of an internal buffer.
         *
         * @param bufferCapacity buffer capacity in bytes.
         * @return this instance for method chaining
         */
        public LineSenderBuilder bufferCapacity(int bufferCapacity) {
            if (this.bufferCapacity != BUFFER_CAPACITY_DEFAULT) {
                throw new LineSenderException("buffer capacity was already configured to " + this.bufferCapacity);
            }
            this.bufferCapacity = bufferCapacity;
            return this;
        }

        /**
         * Advanced TLS configuration. Most users should not need to use this.
         *
         * @return instance of {@link AdvancedTlsSettings} to advanced TLS configuration
         */
        public AdvancedTlsSettings advancedTls() {
            if (LineSenderBuilder.this.trustStorePath != null) {
                throw new LineSenderException("custom trust store was already configured to " + LineSenderBuilder.this.trustStorePath);
            }
            if (tlsValidationMode == TlsValidationMode.INSECURE) {
                throw new LineSenderException("TLS validation was already disabled");
            }
            return new AdvancedTlsSettings();
        }

        /**
         * Build a Sender instance. This method construct a Sender instance.
         *
         * @return returns a configured instance of Sender.
         */
        public Sender build() {
            configureDefaults();
            validateParameters();


            NetworkFacade nf = NetworkFacadeImpl.INSTANCE;
            LineChannel channel = new PlainTcpLineChannel(nf, address.subSequence(0, addressLimit), port, bufferCapacity * 2);
            LineTcpSender sender;
            if (tlsEnabled) {
                assert (trustStorePath == null) == (trustStorePassword == null); //either both null or both non-null
                DelegatingTlsChannel tlsChannel;
                try {
                    tlsChannel = new DelegatingTlsChannel(channel, trustStorePath, trustStorePassword, tlsValidationMode);
                } catch (Throwable t) {
                    closeSilently(channel);
                    throw rethrow(t);
                }
                channel = tlsChannel;
            }
            try {
                sender = new LineTcpSender(channel, bufferCapacity);
            } catch (Throwable t) {
                closeSilently(channel);
                throw rethrow(t);
            }
            if (privateKey != null) {
                try {
                    sender.authenticate(keyId, privateKey);
                } catch (Throwable t) {
                    closeSilently(sender);
                    throw rethrow(t);
                } finally {
                    if (shouldDestroyPrivKey) {
                        try {
                            privateKey.destroy();
                        } catch (DestroyFailedException e) {
                            // not much we can do
                        }
                    }
                }
            }
            return sender;
        }

        private void configureDefaults() {
            if (bufferCapacity == BUFFER_CAPACITY_DEFAULT) {
                bufferCapacity = DEFAULT_BUFFER_CAPACITY;
            }
            if (port == PORT_DEFAULT) {
                port = DEFAULT_PORT;
            }
        }

        private void validateParameters() {
            if (address == null) {
                throw new LineSenderException("questdb server address not set");
            }
            if (!tlsEnabled && trustStorePath != null) {
                throw new LineSenderException("custom trust store configured to " + trustStorePath + ", but TLS was not enabled");
            }
            if (!tlsEnabled && tlsValidationMode != TlsValidationMode.DEFAULT) {
                throw new LineSenderException("TSL validation disabled, but TLS was not enabled");
            }
            if (keyId != null && bufferCapacity < MIN_BUFFER_SIZE_FOR_AUTH) {
                throw new LineSenderException("Minimal buffer capacity is " + MIN_BUFFER_SIZE_FOR_AUTH + ". Requested buffer capacity: " + bufferCapacity);
            }
        }

        private static void closeSilently(Closeable resource) {
            try {
                resource.close();
            } catch (IOException e) {
                // not much we can do
            }
        }

        private static RuntimeException rethrow(Throwable t) {
            if (t instanceof LineSenderException) {
                throw (LineSenderException)t;
            }
            throw new LineSenderException(t);
        }

        /**
         * Auxiliary class to configure client authentication.
         * If you have an instance of {@link PrivateKey} then you can pass it directly.
         * Alternative a private key encoded as a string token can be used too.
         *
         */
        public class AuthBuilder {

            /**
             * Configures a private key for authentication.
             *
             * @param privateKey privateKey to use for authentication
             * @return an instance of LineSenderBuilder for further configuration
             */
            public LineSenderBuilder privateKey(PrivateKey privateKey) {
                LineSenderBuilder.this.privateKey = privateKey;
                return LineSenderBuilder.this;
            }

            /**
             * Create a private key out of a base64 encoded token
             *
             * @param token base64 encoded private key
             * @return an instance of LineSenderBuilder for further configuration
             */
            public LineSenderBuilder token(String token) {
                try {
                    LineSenderBuilder.this.privateKey = AuthDb.importPrivateKey(token);
                } catch (IllegalArgumentException e) {
                    throw new LineSenderException("cannot import token", e);
                }
                LineSenderBuilder.this.shouldDestroyPrivKey = true;
                return LineSenderBuilder.this;
            }
        }

        public class AdvancedTlsSettings {
            /**
             * Configure a custom truststore. This is only needed when using {@link #enableTls()} when your default
             * truststore does not contain certificate chain used by a server. Most users should not need it.
             * <br>
             * The path can be either a path on a local filesystem. Or you can prefix it with "classpath:" to instruct
             * the Sender to load a trust store from a classpath.
             *
             *
             * @param trustStorePath a path to a trust store.
             * @param trustStorePassword a password to for the trustore
             * @return an instance of LineSenderBuilder for further configuration
             */
            public LineSenderBuilder customTrustStore(String trustStorePath, char[] trustStorePassword) {
                LineSenderBuilder.this.trustStorePath = trustStorePath;
                LineSenderBuilder.this.trustStorePassword = trustStorePassword;
                return LineSenderBuilder.this;
            }

            /**
             * This server certification validation altogether.
             * This is suitable when testing self-signed certificate. It's inherently insecure and should
             * never be used in a production.
             * <br>
             * If you cannot use trusted certificate then you should prefer {@link  #customTrustStore(String, char[])}
             * over disabling validation.
             *
             * @return an instance of LineSenderBuilder for further configuration
             */
            public LineSenderBuilder disableCertificateValidation() {
                LineSenderBuilder.this.tlsValidationMode = TlsValidationMode.INSECURE;
                return LineSenderBuilder.this;
            }

        }
    }

    /**
     * Configure TLS mode.
     * Most users should not need to use anything but the default mode.
     *
     */
    enum TlsValidationMode {

        /**
         * Sender validates a server certificate chain and throws an exception
         * when a certificate is not trusted.
         *
         */
        DEFAULT,

        /**
         * Suitable for testing. In this mode Sender does not validate a server certificate chain.
         * This is inherently insecure and should never be used in a production environment.
         * Useful in test environments with self-signed certificates.
         *
         */
        INSECURE
    }
}