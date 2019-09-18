package com.github.yuanrw.im.client.handler;

import com.github.yuanrw.im.client.api.ClientMsgListener;
import com.github.yuanrw.im.common.domain.ack.ClientAckWindow;
import com.github.yuanrw.im.common.domain.ack.ServerAckWindow;
import com.github.yuanrw.im.common.parse.AbstractMsgParser;
import com.github.yuanrw.im.common.parse.AckParser;
import com.github.yuanrw.im.common.parse.InternalParser;
import com.github.yuanrw.im.protobuf.generate.Ack;
import com.github.yuanrw.im.protobuf.generate.Chat;
import com.github.yuanrw.im.protobuf.generate.Internal;
import com.google.protobuf.Message;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Consumer;

import static com.github.yuanrw.im.common.parse.AbstractMsgParser.checkDest;
import static com.github.yuanrw.im.common.parse.AbstractMsgParser.checkFrom;

/**
 * Date: 2019-04-15
 * Time: 22:42
 *
 * @author yrw
 */
public class ClientConnectorHandler extends SimpleChannelInboundHandler<Message> {
    private Logger logger = LoggerFactory.getLogger(ClientConnectorHandler.class);

    private ClientMsgListener clientMsgListener;
    private FromConnectorParser fromConnectorParser;
    private ChannelHandlerContext ctx;

    private ServerAckWindow serverAckWindow;
    private ClientAckWindow clientAckWindow;

    public ClientConnectorHandler(ClientMsgListener clientMsgListener) {
        assert clientMsgListener != null;
        this.clientMsgListener = clientMsgListener;
        this.fromConnectorParser = new FromConnectorParser();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        serverAckWindow = new ServerAckWindow(500, Duration.ofSeconds(2));
        clientAckWindow = new ClientAckWindow(500, ctx);
        clientMsgListener.online();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) throws Exception {
        logger.debug("[client] get msg: {}", msg.toString());

        checkFrom(msg, Internal.InternalMsg.Module.CONNECTOR);
        checkDest(msg, Internal.InternalMsg.Module.CLIENT);

        fromConnectorParser.parse(msg, ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.debug("[client] disconnect to connector");
        clientMsgListener.offline();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("[client] has error: ", cause);
        clientMsgListener.hasException(ctx, cause);
    }

    public void writeAndFlush(Message message, Long id) {
        serverAckWindow.offer(id, message, m -> ctx.writeAndFlush(m))
            .thenAccept(m -> clientMsgListener.hasSent(id))
            .exceptionally(e -> {
                logger.error("[client] waiting for connector's response failed");
                return null;
            });
    }

    public ChannelHandlerContext getCtx() {
        return ctx;
    }

    public ServerAckWindow getServerAckWindow() {
        return serverAckWindow;
    }

    class FromConnectorParser extends AbstractMsgParser {

        @Override
        public void registerParsers() {
            InternalParser internalParser = new InternalParser(3);
            internalParser.register(Internal.InternalMsg.MsgType.ACK, (m, ctx) -> serverAckWindow.ack(m));

            AckParser ackParser = new AckParser(2);
            ackParser.register(Ack.AckMsg.MsgType.DELIVERED, (m, ctx) ->
                offer(m.getId(), m, ignore -> clientMsgListener.hasDelivered(m.getAckMsgId())));

            ackParser.register(Ack.AckMsg.MsgType.READ, (m, ctx) ->
                offer(m.getId(), m, ignore -> clientMsgListener.hasRead(m.getAckMsgId())));

            register(Chat.ChatMsg.class, (m, ctx) ->
                offer(m.getId(), m, ignore -> clientMsgListener.read(m)));

            register(Ack.AckMsg.class, ackParser.generateFun());
            register(Internal.InternalMsg.class, internalParser.generateFun());
        }

        private void offer(Long id, Message m, Consumer<Message> consumer) {
            clientAckWindow.offer(id, Internal.InternalMsg.Module.CLIENT, Internal.InternalMsg.Module.CONNECTOR, m, consumer);
        }
    }

    public void setServerAckWindow(ServerAckWindow serverAckWindow) {
        this.serverAckWindow = serverAckWindow;
    }

    public void setClientAckWindow(ClientAckWindow clientAckWindow) {
        this.clientAckWindow = clientAckWindow;
    }
}