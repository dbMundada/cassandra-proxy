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

import com.datastax.oss.protocol.internal.*;
import com.datastax.oss.protocol.internal.request.Batch;
import com.datastax.oss.protocol.internal.request.Query;
import com.datastax.oss.protocol.internal.response.Error;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.cli.*;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;
import io.vertx.micrometer.backends.BackendRegistries;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/*-h localhost localhost --proxy-pem-keyfile /home/german/Project/cassandra-proxy/src/main/resources/server.pem --proxy-pem-certfile /home/german/Project/cassandra-proxy/src/main/resources/server.key*/
public class Proxy extends AbstractVerticle {
    public static final String UUID = "UUID()";
    public static final String NOW = "NOW()";
    private static final Logger LOG = LoggerFactory.getLogger(Proxy.class);
    public static final String CASSANDRA_SERVER_PORT = "29042";
    public static final String PROTOCOL_VERSION = "protocol-version";
    private static CommandLine commandLine;
    private BufferCodec bufferCodec = new BufferCodec();
    private FrameCodec<BufferCodec.PrimitiveBuffer> serverCodec = FrameCodec.defaultServer(bufferCodec, Compressor.none());
    private FrameCodec<BufferCodec.PrimitiveBuffer> clientCodec = FrameCodec.defaultClient(bufferCodec, Compressor.none());
    private final UUIDGenWrapper uuidGenWrapper;


    public Proxy() {
        this.uuidGenWrapper = new UUIDGenWrapper();
    }

    //for tests
    public Proxy(UUIDGenWrapper uuidGenWrapper, FrameCodec<BufferCodec.PrimitiveBuffer> serverCodec, FrameCodec<BufferCodec.PrimitiveBuffer> clientCodec) {
        this.uuidGenWrapper = uuidGenWrapper;
        this.serverCodec = serverCodec;
        this.clientCodec = clientCodec;
    }

    public static void main(String[] args) {
        CLI cli = CLI.create("cassandra-proxy")
                .setSummary("A dual write proxy for cassandra.")
                .addOption(
                        new Option().setLongName("help").setShortName("h").setFlag(true).setHelp(true))
                .addOption(new TypedOption<Boolean>()
                        .setType(Boolean.class)
                        .setLongName("wait")
                        .setShortName("W")
                        .setDescription("wait for write completed on both clusters")
                        .setFlag(true)
                        .setDefaultValue("true"))
                .addArgument(new Argument()
                        .setDescription("Source cluster. This is the cluster which is authoritative for reads")
                        .setRequired(true)
                        .setArgName("source"))
                .addOption(new TypedOption<Integer>()
                        .setType(Integer.class)
                        .setDescription("Source cluster port. This is the cluster which is authoritative for reads")
                        .setLongName("source-port")
                        .setDefaultValue("9042"))
                .addOption(new Option()
                        .setDescription("Source cluster identifier. This is an identifier used in logs and metrics for the source cluster")
                        .setLongName("source-identifier")
                        .setDefaultValue("source node"))
                .addArgument(new Argument()
                        .setRequired(true)
                        .setDescription("Destination cluster. This is the cluster we ignore reads for.")
                        .setArgName("target"))
                .addOption(new TypedOption<Integer>()
                        .setType(Integer.class)
                        .setDescription("Destination cluster port. This is the cluster we ignore reads for.")
                        .setLongName("target-port")
                        .setDefaultValue("9042"))
                .addOption(new Option()
                        .setDescription("Target cluster identifier. This is an identifier used in logs and metrics for the target cluster")
                        .setLongName("target-identifier")
                        .setDefaultValue("target node"))
                .addOption(new TypedOption<Integer>()
                        .setType(Integer.class)
                        .setDescription("Port number the proxy listens under")
                        .setLongName("proxy-port")
                        .setShortName("p")
                        .setDefaultValue(CASSANDRA_SERVER_PORT))
                .addOption(new Option()
                        .setDescription("Pem file containing the key for the proxy to perform TLS encryption. If not set, no encryption ")
                        .setLongName("proxy-pem-keyfile"))
                .addOption(new Option()
                        .setDescription("Pem file containing the server certificate for the proxy to perform TLS encryption. If not set, no encryption ")
                        .setLongName("proxy-pem-certfile"))
                .addOption(new Option()
                        .setDescription("Jks containing the key for the proxy to perform TLS encryption. If not set, no encryption ")
                        .setLongName("proxy-jks-file"))
                .addOption(new TypedOption<Integer>()
                        .setType(Integer.class)
                        .setDescription("How many threads should be launched")
                        .setLongName("threads")
                        .setShortName("t")
                        .setDefaultValue("1"))
                .addOption(new TypedOption<Integer>()
                        .setType(Integer.class)
                        .setDescription("Supported Cassandra Protocol Version(s). If not set return what source server says")
                        .setLongName(PROTOCOL_VERSION)
                        .setMultiValued(true))
                .addOption(new Option()
                        .setDescription("Supported Cassandra CQL Version(s). If not set return what source server says")
                        .setLongName("cql-version")
                        .setMultiValued(true))
                .addOption(new TypedOption<Boolean>()
                        .setType(Boolean.class)
                        .setLongName("uuid")
                        .setDescription("scan for uuid and generate on proxy for inserts/updates")
                        .setDefaultValue("true")
                        .setFlag(true))
                .addOption(new TypedOption<Boolean>()
                        .setType(Boolean.class)
                        .setLongName("metrics")
                        .setDescription("provide metrics and start metrics server")
                        .setDefaultValue("true")
                        .setFlag(true))
                .addOption(new TypedOption<Integer>()
                        .setType(Integer.class)
                        .setDescription("Port number the promethwus metrics are available")
                        .setLongName("metrics-port")
                        .setShortName("mp")
                        .setDefaultValue("28000"));

        // TODO: Add trust store, client certs, etc.

        try {
            commandLine = cli.parse(Arrays.asList(args));
        } catch (CLIException e) {
            System.out.println(e.getMessage());
            help(cli);
            System.exit(-1);
        }

        // The parsing does not fail and let you do:
        if (!commandLine.isValid() && commandLine.isAskingForHelp()) {
            help(cli);
            System.exit(-1);
        }

        for (Option o : cli.getOptions()) {
            LOG.info(o.getName() + " : " + commandLine.getOptionValue(o.getName()));
        }

        for (Argument a : cli.getArguments()) {
            LOG.info(a.getArgName() + " : " + commandLine.getArgumentValue(a.getArgName()));
        }

        LOG.info("Cassandra Proxy starting...");
        VertxOptions options = new VertxOptions();
        //  Micrometer
        if (commandLine.getOptionValue("metrics")) {
            options.setMetricsOptions(
                    new MicrometerMetricsOptions()
                            .setPrometheusOptions(new VertxPrometheusOptions().setEnabled(true)
                                    .setStartEmbeddedServer(true)
                                    .setEmbeddedServerOptions(new HttpServerOptions().setPort(commandLine.getOptionValue("metrics-port"))))
                                    .setEnabled(true)
                                    .setJvmMetricsEnabled(true));
            LOG.info("Cassandra Proxy Metrics at port: {}", (Integer)commandLine.getOptionValue("metrics-port"));
        }

        Vertx vertx = Vertx.vertx(options);
        for (int i = 0; i < (Integer) commandLine.getOptionValue("threads"); i++) {
            vertx.deployVerticle(new Proxy());
        }
    }

    private static void help(CLI cli) {
        StringBuilder builder = new StringBuilder();
        cli.usage(builder);
        System.out.print(builder.toString());
    }

    @Override
    public void start() throws Exception {

        NetServerOptions options = new NetServerOptions().setPort(commandLine.getOptionValue("proxy-port"));
        if (commandLine.getOptionValue("proxy-pem-keyfile") != null && commandLine.getOptionValue("proxy-pem-certfile") != null) {
            PemKeyCertOptions pemOptions = new PemKeyCertOptions();
            pemOptions.addCertPath(commandLine.getOptionValue("proxy-pem-keyfile"))
                    .addKeyPath(commandLine.getOptionValue("proxy-pem-certfile"));
            options.setSsl(true).setPemKeyCertOptions(pemOptions);
        } else if (commandLine.getOptionValue("proxy-pem-keyfile") != null || commandLine.getOptionValue("proxy-pem-certfile") != null) {
            System.out.println("Both proxy-pem-keyfile and proxy-pem-certfile need to be set for TLS");
            LOG.error("Both proxy-pem-keyfile and proxy-pem-certfile need to be set for TLS");
            System.exit(-1);
        }

        List<String> protocolVersions = new ArrayList<>();
        if (commandLine.getOptionValues(PROTOCOL_VERSION) != null) {
            for (Object protocolVersion : commandLine.getOptionValues(PROTOCOL_VERSION)) {
                protocolVersions.add(protocolVersion + "/v" + protocolVersion);
            }
        }

        NetServer server = vertx.createNetServer(options);

        server.connectHandler(socket -> {
            ProxyClient client1 = new ProxyClient(commandLine.getOptionValue("source-identifier"), socket, protocolVersions, commandLine.getOptionValues("cql-version"), commandLine.getOptionValue("metrics"), commandLine.getOptionValue("wait"));
            Future c1 = client1.start(vertx, commandLine.getArgumentValue("source"), commandLine.getOptionValue("source-port"));
            ProxyClient client2 = new ProxyClient(commandLine.getOptionValue("target-identifier"),  commandLine.getOptionValue("metrics"));
            Future c2 = client2.start(vertx, commandLine.getArgumentValue("target"), commandLine.getOptionValue("target-port"));
            LOG.info("Connection to both Cassandra servers up)");
            FastDecode fastDecode = FastDecode.newFixed(socket, buffer -> {
                final long startTime = System.nanoTime();
                final int opcode = buffer.getByte(4);
                FastDecode.State state = FastDecode.quickLook(buffer);
                // Check if we support the protocol version
                if (commandLine.getOptionValues(PROTOCOL_VERSION) != null && !commandLine.getOptionValues(PROTOCOL_VERSION).isEmpty()) {
                    int protocolVersion = buffer.getByte(0) & 0b0111_1111;
                    if (!isProtocolSupported(protocolVersion)) {
                        LOG.info("Downgrading Protocol from {}", protocolVersion);
                        writeToClientSocket(socket, client1, client2, errorProtocolNotSupported(buffer, startTime, opcode, state, protocolVersion));
                        return;
                    }
                }

                //Todo: Do we need to fake peers? Given that we would need to also come up with tokens that seems
                // future work when C* is smart enough to deal with multiple C* on the same node but on different ports
                // right now it will always connect to proxy if we set the proxy port even if there is C* running
                // on another port.


                if ((Boolean) commandLine.getOptionValue("uuid")
                        && state == FastDecode.State.query
                        && scanForUUID(buffer)) {
                    buffer = handleUUID(buffer);
                }
                final long endTime = System.nanoTime();
                Future<Buffer> f1 = client1.writeToServer(buffer).future();
                Future<Buffer> f2 = client2.writeToServer(buffer).future();
                CompositeFuture.all(f1, f2).onComplete(e -> {
                    Buffer buf = f1.result();
                    if (commandLine.getOptionValue("metrics")) {
                        sendMetrics(startTime, opcode, state, endTime, f1, f2, buf);
                    }

                    if (commandLine.getOptionValue("wait")) {
                        // we waited for both results - now write to client
                        writeToClientSocket(socket, client1, client2, buf);
                    }
                });
            });
            fastDecode.endHandler(x -> {
                LOG.info("Connection closed!");
            });

        }).listen(res -> {
            if (res.succeeded()) {
                LOG.info("Server is now listening on  port: " + server.actualPort());
            } else {
                LOG.error("Failed to bind!");
                System.exit(-1);
            }
        });

    }

    private Buffer errorProtocolNotSupported(Buffer buffer, long startTime, int opcode, FastDecode.State state, int protocolVersion) {
        StringBuilder supported = new StringBuilder("Invalid or unsupported protocol version (");
        supported = supported.append(protocolVersion).append("); supported versions are (");
        Iterator i = commandLine.getOptionValues(PROTOCOL_VERSION).iterator();
        while (i.hasNext()) {
            Object o = i.next();
            supported = supported.append(o).append("/v").append(o);
            if (i.hasNext()) {
                supported = supported.append(",");
            }
        }
        supported = supported.append(")");

        // generate a protocol error
        Error e = new Error(10, supported.toString());
        int streamId = buffer.getShort(2);
        Frame f = Frame.forResponse((Integer) commandLine.getOptionValues(PROTOCOL_VERSION).get(0), streamId, null, Collections.emptyMap(), Collections.emptyList(), e);
        if (commandLine.getOptionValue("metrics")) {
            MeterRegistry registry = BackendRegistries.getDefaultNow();
            Timer.builder("cassandraProxy.cqlOperation.proxyTime")
                    .tag("requestOpcode", String.valueOf(opcode))
                    .tag("requestState", state.toString()).register(registry)
                    .record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
            Timer.builder("cassandraProxy.cqlOperation.timer")
                    .tag("requestOpcode", String.valueOf(opcode))
                    .tag("requestState", state.toString())
                    .register(registry)
                    .record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
            Counter.builder("cassandraProxy.cqlOperation.cqlServerErrorCount")
                    .tag("requestOpcode", String.valueOf(opcode))
                    .tag("requestState", state.toString())
                    .register(registry).increment();
        }
        return serverCodec.encode(f).buffer;
    }

    private boolean isProtocolSupported(int protocolVersion) {
        for (Object o : commandLine.getOptionValues(PROTOCOL_VERSION)) {
            if (o.equals(protocolVersion)) {
                return true;
            }
        }
        return false;
    }

    private void writeToClientSocket(io.vertx.core.net.NetSocket socket, ProxyClient client1, ProxyClient client2, Buffer buf) {
        socket.write(buf);
        if (socket.writeQueueFull()) {
            LOG.warn("Pausing processing");
            client1.pause();
            client2.pause();
            final long startPause = System.nanoTime();
            socket.drainHandler(done -> {
                LOG.warn("Resuming processing");
                client1.resume();
                client2.resume();
                if (commandLine.getOptionValue("metrics")) {
                    MeterRegistry registry = BackendRegistries.getDefaultNow();
                    Timer.builder("cassandraProxy.clientSocket.paused")
                            .tag("clientAddress", socket.remoteAddress().toString())
                            .tag("wait", commandLine.getOptionValue("wait").toString())
                            .register(registry)
                            .record(System.nanoTime() - startPause, TimeUnit.NANOSECONDS);
                }
            });
        }
    }

    private void sendMetrics(long startTime, int opcode, FastDecode.State state, long endTime, Future<Buffer> f1, Future<Buffer> f2, Buffer buf) {
        MeterRegistry registry = BackendRegistries.getDefaultNow();
        if (FastDecode.quickLook(buf) == FastDecode.State.error) {
            Counter.builder("cassandraProxy.cqlOperation.cqlServerErrorCount")
                    .tag("requestOpcode", String.valueOf(opcode))
                    .tag("requestState", state.toString())
                    .register(registry).increment();
        }
        if (!f1.result().equals(f2.result())) {
            // @Todo: peers will almost always return a different result so fiter some of them out
            //      eventually to give a more realistic metric
            Counter.builder("cassandraProxy.cqlOperation.cqlDifferentResultCount")
                    .tag("requestOpcode", String.valueOf(opcode))
                    .tag("requestState", state.toString())
                    .register(registry).increment();
            LOG.info("Diferent result");
            LOG.debug("Recieved cassandra server 1: {}", f1.result());
            LOG.debug("Recieved cassandra server 2: {}",  f2.result());
        }
        Timer.builder("cassandraProxy.cqlOperation.proxyTime")
                .tag("requestOpcode", String.valueOf(opcode))
                .tag("requestState", state.toString()).register(registry)
                .record(endTime - startTime, TimeUnit.NANOSECONDS);
        Timer.builder("cassandraProxy.cqlOperation.timer")
                .tag("requestOpcode", String.valueOf(opcode))
                .tag("requestState", state.toString())
                .register(registry)
                .record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
    }

    protected Buffer handleUUID(Buffer buffer) {
        BufferCodec.PrimitiveBuffer buffer2 = BufferCodec.createPrimitiveBuffer(buffer);
        try {
            Frame f = serverCodec.decode(buffer2);
            LOG.info("Recieved: {}",  f.message);
            Message newMessage = f.message;
            if (f.message instanceof Query) {
                Query q = (Query) f.message;
                // Ideally we would be more targeted in replacing especially for
                // UPDATE and just target the SET part or the VALUES part
                // BATCH at least run by cqlsh will also come in as Query and not Batch type
                // so we handle this here as well.
                if (q.query.toUpperCase().startsWith("INSERT")
                        || q.query.toUpperCase().startsWith("UPDATE")
                        || (q.query.toUpperCase().startsWith("BEGIN BATCH") && (
                        q.query.toUpperCase().contains("INSERT") || q.query.toUpperCase().contains("UPDATE")))) {
                    String s = getReplacedQuery(q.query, UUID);
                    s = getReplacedQuery(s, NOW);
                    newMessage = new Query(s, q.options);
                }
            } else if (f.message instanceof Batch) {
                // Untested...
                Batch b = (Batch) f.message;
                List<Object> queriesOrIds = new ArrayList<>();
                for (Object o : b.queriesOrIds) {
                    if (o instanceof String) {
                        // it's a query and not just an id
                        String s = getReplacedQuery((String) o, UUID);
                        o = getReplacedQuery(s, NOW);
                    }
                    queriesOrIds.add(o);
                }
                List<List<ByteBuffer>> values = new ArrayList<>();
                for (List<ByteBuffer> list : b.values) {
                    List<ByteBuffer> v = new ArrayList<>();
                    for (ByteBuffer bb : list) {
                        String s = bb.toString();
                        if (s.trim().equalsIgnoreCase(UUID) || s.trim().equalsIgnoreCase(NOW)) {
                            ByteBuffer newBB = ByteBuffer.wrap(uuidGenWrapper.getTimeUUID().toString().getBytes());
                            v.add(newBB);
                            LOG.info("replaced {}  with {}", s, newBB);
                        } else {
                            v.add(bb);
                        }
                    }
                    values.add(v);
                }
                newMessage = new Batch(b.type, queriesOrIds, values, b.consistency, b.serialConsistency, b.defaultTimestamp, b.keyspace, b.nowInSeconds);
            }
            //  TODO: transform out prepared statement
            LOG.info("Replaced: {}", newMessage);
            Frame g = Frame.forRequest(f.protocolVersion, f.streamId, f.tracing, f.customPayload, newMessage);
            buffer = clientCodec.encode(g).buffer;
        } catch (Exception e) {
            LOG.error("Exception during decoding: ", e);
        }
        return buffer;
    }

    protected String getReplacedQuery(String q, String search) {
        int i = q.toUpperCase().indexOf(search);
        int j = 0;
        StringBuilder sb = new StringBuilder();
        while (i != -1) {
            sb.append(q.substring(j, i));
            j = i + search.length();
            sb.append(uuidGenWrapper.getTimeUUID());
            i = q.toUpperCase().indexOf(UUID, j);
        }
        sb.append(q.substring(j));
        return sb.toString();
    }

    private boolean scanForUUID(Buffer buffer) {
        String s = buffer.getString(9, buffer.length());
        return s.toUpperCase().contains(UUID) || s.toUpperCase().contains(NOW);
    }

}