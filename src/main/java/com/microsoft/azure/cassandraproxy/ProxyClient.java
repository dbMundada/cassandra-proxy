/*
 * Copyright Microsoft Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.microsoft.azure.cassandraproxy;

import com.datastax.oss.protocol.internal.Compressor;
import com.datastax.oss.protocol.internal.Frame;
import com.datastax.oss.protocol.internal.FrameCodec;
import com.datastax.oss.protocol.internal.ProtocolConstants;
import com.datastax.oss.protocol.internal.response.Supported;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.NetClientOptions;
import io.vertx.micrometer.backends.BackendRegistries;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProxyClient  {
    private static final Logger LOG = LoggerFactory.getLogger(ProxyClient.class);
    private static final int CASSANDRA_PROTOCOL_VERSION = ProtocolConstants.Version.V3;
    private static final BufferCodec bufferCodec = new BufferCodec();
    private static final FrameCodec<BufferCodec.PrimitiveBuffer> serverCodec = FrameCodec.defaultServer(bufferCodec, Compressor.none());
    private static final FrameCodec<BufferCodec.PrimitiveBuffer> clientCodec = FrameCodec.defaultClient(bufferCodec, Compressor.none());
    private final String identifier;
    private final List<String> protocolVersions;
    private final List<String> cqlVersions;
    private final boolean metrics;
    private final boolean wait;
    private NetSocket socket;
    private Promise<Void> socketPromise;
    private Promise<Buffer> bufferPromise = Promise.promise();
    private final NetSocket serverSocket;
    private FastDecode fastDecode;
    // Map to hold the requests so we can assign out of order responses
    // to the right request Promise
    private Map<Short, Promise> results = new ConcurrentHashMap<>();


    public ProxyClient(String identifier, NetSocket socket, List<String> protocolVersions, List<String> cqlVersions, boolean metrics, boolean wait) {
        this.identifier = identifier;
        this.serverSocket = socket;
        this.protocolVersions = protocolVersions;
        this.cqlVersions = cqlVersions;
        this.metrics = metrics;
        this.wait = wait;
    }

    public ProxyClient(String identifier, boolean metrics) {
        this(identifier, null, null, null, metrics, true);
    }

    public void pause() {
        fastDecode.pause();
    }

    public void resume() {
        fastDecode.resume();
    }

    public Future<Void> start(Vertx vertx,String host, int port)
    {
        socketPromise = Promise.promise();
        // @TODO: Allow for truststore, etc,
        NetClientOptions options = new NetClientOptions().
                setSsl(true).
                setTrustAll(true);
        vertx.createNetClient(options).connect(port, host, res-> {
            if (res.succeeded()) {
                LOG.info("Server connected");
                socket = res.result();
                fastDecode = FastDecode.newFixed(socket, b-> clientHandle(b));
                fastDecode.endHandler(x->{LOG.info("Server connection closed");});
                socketPromise.complete();
            }  else {
                LOG.error("Couldn't connect to server");
                socketPromise.fail("Couldn't connect to server");
            }
        });
        return socketPromise.future();
    }

    public Promise<Buffer> writeToServer(Buffer buffer) {
        bufferPromise = Promise.promise();
        results.put(buffer.getShort(2), bufferPromise);
        if (socketPromise != null) {
            socketPromise.future().onSuccess(t -> {
                write(buffer);
            });
        } else {
           write(buffer);
        }

        return bufferPromise;
    }

    private void write(Buffer buffer) {
        socket.write(buffer);
        if (socket.writeQueueFull()) {
            LOG.warn("{} Write Queue full!", identifier);
            final long startPause = System.nanoTime();
            if (serverSocket != null) {
                serverSocket.pause();
                socket.drainHandler(done -> {
                    LOG.warn("Resume processing");
                    serverSocket.resume();
                    if (metrics) {
                        MeterRegistry registry = BackendRegistries.getDefaultNow();
                        Timer.builder("cassandraProxy.serverSocket.paused")
                                .tag("serverAddress", socket.remoteAddress().toString())
                                .tag("severIdentifier", identifier)
                                .register(registry)
                                .record(System.nanoTime() - startPause, TimeUnit.NANOSECONDS);
                    }
                });
            }
        }
    }


    private void clientHandle(Buffer buffer)
    {
            FastDecode.State state = fastDecode.quickLook(buffer);
            // Handle Supported
            if ((serverSocket != null) && (state == FastDecode.State.supported) && (!protocolVersions.isEmpty() || !cqlVersions.isEmpty())) {
                BufferCodec.PrimitiveBuffer buffer2 = BufferCodec.createPrimitiveBuffer(buffer);
                Frame r = clientCodec.decode(buffer2);
                Supported supported = (Supported) r.message;
                LOG.info("Recieved from Server {} : {}", identifier, supported);
                // INFO: Recieved from Server client2:SUPPORTED {PROTOCOL_VERSIONS=[3/v3, 4/v4, 5/v5-beta], COMPRESSION=[snappy, lz4], CQL_VERSION=[3.4.4]}
                Map<String, List<String>> options = new HashMap<>(supported.options);
                if ((protocolVersions != null) && (!protocolVersions.isEmpty())) {
                    options.put("PROTOCOL_VERSIONS", protocolVersions);
                }
                if ((cqlVersions !=null) && (!cqlVersions.isEmpty())) {
                    options.put("CQL_VERSION", cqlVersions);
                }
                supported = new Supported(options);
                LOG.info("Sending to Client {} : {}", identifier, supported);
                Frame f = Frame.forResponse(r.protocolVersion, r.streamId, r.tracingId, r.customPayload , r.warnings, supported);
                sendResult(serverCodec.encode(f).buffer);
                return;
            }

            // TODO: Do something for event
            if (state == FastDecode.State.event || state == FastDecode.State.error) {
                try {
                    BufferCodec.PrimitiveBuffer buffer2 = BufferCodec.createPrimitiveBuffer(buffer);
                    Frame r = clientCodec.decode(buffer2);
                    LOG.info("Recieved from Server {} : {}", identifier , r.message);
                    sendResult(buffer);
                } catch (Exception e) {
                    LOG.error("Failed decoding: ", e);
                    if (socketPromise.tryComplete()) {
                       sendResult(buffer);
                    }
                }
            } else {
               sendResult(buffer);
            }
    }

    private void sendResult(Buffer buffer) {
        if (!wait && socket != null) {
            socket.write(buffer);
            if (socket.writeQueueFull()) {
                LOG.warn("Pausing processing");
                this.pause();
                final long startPause = System.nanoTime();
                socket.drainHandler(done -> {
                    LOG.warn("Resuming processing");
                    this.resume();
                    if (metrics) {
                        MeterRegistry registry = BackendRegistries.getDefaultNow();
                        Timer.builder("cassandraProxy.clientSocket.paused")
                                .tag("clientAddress", socket.remoteAddress().toString())
                                .tag("wait", String.valueOf(wait)).register(registry)
                                .record(System.nanoTime() - startPause, TimeUnit.NANOSECONDS);
                    }
                });
            }
        }
        short streamId = buffer.getShort(2);
        if (results.containsKey(streamId)) {
            if (!results.get(streamId).tryComplete(buffer)) {
                LOG.warn("out of band: {}", buffer);
            }
            results.remove(streamId); // we are done with that
        } else {
            LOG.warn ("Stream Id {} no registered. Are you using TLS on a non TLS connection?", streamId);
        }
    }

}