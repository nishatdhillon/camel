/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.mina;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.Map;

import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.ExchangePattern;
import org.apache.camel.converter.ObjectConverter;
import org.apache.camel.impl.DefaultComponent;
import org.apache.mina.common.ByteBuffer;
import org.apache.mina.common.IoAcceptor;
import org.apache.mina.common.IoConnector;
import org.apache.mina.common.IoServiceConfig;
import org.apache.mina.common.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFactory;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.ProtocolEncoder;
import org.apache.mina.filter.codec.ProtocolEncoderOutput;
import org.apache.mina.filter.codec.serialization.ObjectSerializationCodecFactory;
import org.apache.mina.filter.codec.textline.TextLineCodecFactory;
import org.apache.mina.filter.LoggingFilter;
import org.apache.mina.transport.socket.nio.DatagramAcceptor;
import org.apache.mina.transport.socket.nio.DatagramAcceptorConfig;
import org.apache.mina.transport.socket.nio.DatagramConnector;
import org.apache.mina.transport.socket.nio.DatagramConnectorConfig;
import org.apache.mina.transport.socket.nio.SocketAcceptor;
import org.apache.mina.transport.socket.nio.SocketAcceptorConfig;
import org.apache.mina.transport.socket.nio.SocketConnector;
import org.apache.mina.transport.socket.nio.SocketConnectorConfig;
import org.apache.mina.transport.vmpipe.VmPipeAcceptor;
import org.apache.mina.transport.vmpipe.VmPipeAddress;
import org.apache.mina.transport.vmpipe.VmPipeConnector;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The component for using Apache MINA.
 * 
 * @version $Revision$
 */
public class MinaComponent extends DefaultComponent<MinaExchange> {
    private static final transient Log LOG = LogFactory.getLog(MinaComponent.class);

    // encoder used for datagram
    private CharsetEncoder encoder;

    public MinaComponent() {
    }

    public MinaComponent(CamelContext context) {
        super(context);
    }

    @Override
    protected Endpoint<MinaExchange> createEndpoint(String uri, String remaining, Map parameters) throws Exception {
        URI u = new URI(remaining);

        String protocol = u.getScheme();
        if (protocol.equals("tcp")) {
            return createSocketEndpoint(uri, u, parameters);
        }
        else if (protocol.equals("udp") || protocol.equals("mcast") || protocol.equals("multicast")) {
            return createDatagramEndpoint(uri, u, parameters);
        }
        else if (protocol.equals("vm")) {
            return createVmEndpoint(uri, u);
        }
        else {
            throw new IllegalArgumentException("Unrecognised MINA protocol: " + protocol + " for uri: " + uri);
        }
    }

    protected MinaEndpoint createVmEndpoint(String uri, URI connectUri) {
        IoAcceptor acceptor = new VmPipeAcceptor();
        SocketAddress address = new VmPipeAddress(connectUri.getPort());
        IoConnector connector = new VmPipeConnector();
        return new MinaEndpoint(uri, this, address, acceptor, null, connector, null, false, 0, false);
    }

    protected MinaEndpoint createSocketEndpoint(String uri, URI connectUri, Map parameters) {
        IoAcceptor acceptor = new SocketAcceptor();
        SocketAddress address = new InetSocketAddress(connectUri.getHost(), connectUri.getPort());
        IoConnector connector = new SocketConnector();

        // connector config
        SocketConnectorConfig connectorConfig = new SocketConnectorConfig();
        configureSocketCodecFactory(connectorConfig, parameters);
        connectorConfig.getFilterChain().addLast("logger", new LoggingFilter());

        // acceptor connectorConfig
        SocketAcceptorConfig acceptorConfig = new SocketAcceptorConfig();
        configureSocketCodecFactory(acceptorConfig, parameters);
        acceptorConfig.setReuseAddress(true);
        acceptorConfig.setDisconnectOnUnbind(true);
        acceptorConfig.getFilterChain().addLast("logger", new LoggingFilter());

        boolean lazySessionCreation = ObjectConverter.toBool(parameters.get("lazySessionCreation"));
        long timeout = getTimeoutParameter(parameters);
        boolean transferExchange = ObjectConverter.toBool(parameters.get("transferExchange"));

        MinaEndpoint endpoint = new MinaEndpoint(uri, this, address, acceptor, acceptorConfig, connector, connectorConfig, lazySessionCreation, timeout, transferExchange);

        boolean sync = ObjectConverter.toBool(parameters.get("sync"));
        if (sync) {
            endpoint.setExchangePattern(ExchangePattern.InOut);
        } else {
            endpoint.setExchangePattern(ExchangePattern.InOnly);
        }

        return endpoint;
    }

    protected void configureSocketCodecFactory(IoServiceConfig config, Map parameters) {
        ProtocolCodecFactory codecFactory = getCodecFactory(parameters);

        if (codecFactory == null) {
            boolean textline = ObjectConverter.toBool(parameters.get("textline"));
            if (textline) {
                Charset encoding = getEncodingParameter(parameters);
                codecFactory = new TextLineCodecFactory(encoding);
                LOG.debug("Using TextLineCodecFactory: " + codecFactory + " using encoding: " + encoding);
            }
            else {
                codecFactory = new ObjectSerializationCodecFactory();
                LOG.debug("Using ObjectSerializationCodecFactory: " + codecFactory);
            }
        }

        addCodecFactory(config, codecFactory);
    }

    protected MinaEndpoint createDatagramEndpoint(String uri, URI connectUri, Map parameters) {
        IoAcceptor acceptor = new DatagramAcceptor();
        SocketAddress address = new InetSocketAddress(connectUri.getHost(), connectUri.getPort());
        IoConnector connector = new DatagramConnector();

        DatagramConnectorConfig connectorConfig = new DatagramConnectorConfig();
        configureDataGramCodecFactory(connectorConfig, parameters);
        connectorConfig.getFilterChain().addLast("logger", new LoggingFilter());

        DatagramAcceptorConfig acceptorConfig = new DatagramAcceptorConfig();
        configureDataGramCodecFactory(acceptorConfig, parameters);
        acceptorConfig.setDisconnectOnUnbind(true);
        // reuse address is default true for datagram
        acceptorConfig.getFilterChain().addLast("logger", new LoggingFilter());

        boolean lazySessionCreation = ObjectConverter.toBool(parameters.get("lazySessionCreation"));
        long timeout = getTimeoutParameter(parameters);
        boolean transferExchange = false; // transfer exchange is not supported for datagram protocol

        MinaEndpoint endpoint = new MinaEndpoint(uri, this, address, acceptor, acceptorConfig, connector, connectorConfig, lazySessionCreation, timeout, transferExchange);

        boolean sync = ObjectConverter.toBool(parameters.get("sync"));
        if (sync) {
            endpoint.setExchangePattern(ExchangePattern.InOut);
        } else {
            endpoint.setExchangePattern(ExchangePattern.InOnly);
        }

        return endpoint;
    }

    private Charset getEncodingParameter(Map parameters) {
        String encoding = (String) parameters.get("encoding");
        if (encoding == null) {
            encoding = Charset.defaultCharset().name();
            LOG.debug("No encoding parameter using default charset: " + encoding);
        }
        if (!Charset.isSupported(encoding)) {
            throw new IllegalArgumentException("The encoding: " + encoding + " is not supported");
        }

        return Charset.forName(encoding);
    }

    private static long getTimeoutParameter(Map parameters) throws IllegalArgumentException {
        long timeout = 0;
        String value = (String) parameters.get("timeout");
        if (value != null) {
            try {
                timeout = ObjectConverter.toLong(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("The timeout parameter is not a number: " + value);
            }
        }

        return timeout;
    }

    /**
     * For datagrams the entire message is available as a single ByteBuffer so lets just pass those around by default
     * and try converting whatever they payload is into ByteBuffers unless some custom converter is specified
     */
    protected void configureDataGramCodecFactory(IoServiceConfig config, Map parameters) {

        ProtocolCodecFactory codecFactory = getCodecFactory(parameters);
        if (codecFactory == null) {
            codecFactory = new ProtocolCodecFactory() {
                public ProtocolEncoder getEncoder() throws Exception {
                    return new ProtocolEncoder() {
                        public void encode(IoSession session, Object message, ProtocolEncoderOutput out) throws Exception {
                            ByteBuffer buf = toByteBuffer(message);
                            buf.flip();
                            out.write(buf);
                        }

                        public void dispose(IoSession session) throws Exception {
                        }
                    };
                }

                public ProtocolDecoder getDecoder() throws Exception {
                    return new ProtocolDecoder() {
                        public void decode(IoSession session, ByteBuffer in, ProtocolDecoderOutput out) throws Exception {
                            // must acquire the bytebuffer since we just pass it below instead of creating a new one (CAMEL-257)
                            in.acquire();

                            // lets just pass the ByteBuffer in
                            out.write(in);
                        }

                        public void finishDecode(IoSession session, ProtocolDecoderOutput out) throws Exception {
                        }

                        public void dispose(IoSession session) throws Exception {
                        }
                    };
                }
            };

            // set the encoder used for this datagram codec factory
            Charset encoding = getEncodingParameter(parameters);
            encoder = encoding.newEncoder();

            LOG.debug("Using CodecFactory: " + codecFactory + " using encoding: " + encoding);
        }

        addCodecFactory(config, codecFactory);
    }

    protected ByteBuffer toByteBuffer(Object message) throws CharacterCodingException {
        ByteBuffer answer = convertTo(ByteBuffer.class, message);
        if (answer == null) {
            String value = convertTo(String.class, message);
            answer = ByteBuffer.allocate(value.length()).setAutoExpand(true);

            if (value != null) {
                answer.putString(value, encoder);
            }
        }
        return answer;
    }

    protected ProtocolCodecFactory getCodecFactory(Map parameters) {
        ProtocolCodecFactory codecFactory = null;
        String codec = (String) parameters.get("codec");
        if (codec != null) {
            codecFactory = getCamelContext().getRegistry().lookup(codec, ProtocolCodecFactory.class);
            LOG.debug("Using custom CodecFactory: " + codecFactory);
        }
        return codecFactory;
    }

    protected void addCodecFactory(IoServiceConfig config, ProtocolCodecFactory codecFactory) {
        config.getFilterChain().addLast("codec", new ProtocolCodecFilter(codecFactory));
    }
    
}
