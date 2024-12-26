package websocket;


import cn.hutool.core.date.DateUtil;
import com.alibaba.fastjson.JSON;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.channels.Channel;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class WebSocketServerHandler extends SimpleChannelInboundHandler<Object> {

    private WebSocketServerHandshaker handShaker;

    private ConcurrentHashMap<String, Channel> sessions = new ConcurrentHashMap<>();

    @Override
    protected void messageReceived(ChannelHandlerContext ctx, Object msg) {
        // 传统的HTTP接入
        if (msg instanceof FullHttpRequest) {
            handleHttpRequest(ctx, (FullHttpRequest) msg);
        }
        // WebSocket接入
        else if (msg instanceof WebSocketFrame) {
            log.info("new connection is {}", ctx.channel().id().asShortText());
            sessions.put(ctx.channel().id().asShortText(), (Channel) ctx.channel());
            handleWebSocketFrame(ctx, (WebSocketFrame) msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    private void handleHttpRequest(ChannelHandlerContext ctx,
                                   FullHttpRequest req) {
        log.info("这里再处理http请求");
        // 如果 http解码失败 返回错误
        if (!req.getDecoderResult().isSuccess()) {
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.BAD_REQUEST));
            return;
        }

        // 如果是 websocket 握手
        if (("websocket".equals(req.headers().get("Upgrade")))) {
            log.info("channel is {}", ctx.channel());
            WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                    "ws:" + ctx.channel(), null, false);
            handShaker = wsFactory.newHandshaker(req);
            if (handShaker == null) {
                WebSocketServerHandshakerFactory
                        .sendUnsupportedWebSocketVersionResponse(ctx.channel());
            } else {
                handShaker.handshake(ctx.channel(), req);
            }
            return;
        }
        // http请求
        String uri = req.getUri();
        Map<String, String> resMap = new HashMap<>();
        resMap.put("method", req.getMethod().name());
        resMap.put("uri", uri);
        String msg = "<html><head><title>test</title></head><body>你的请求为：" + JSON.toJSONString(resMap) + "</body></html>";
        // 创建http响应
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                Unpooled.copiedBuffer(msg, CharsetUtil.UTF_8));
        // 设置头信息
        response.headers().set(HttpHeaders.Names.CONTENT_TYPE, "text/html; charset=UTF-8");
        // 将html write到客户端
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);


    }

    private void handleWebSocketFrame(ChannelHandlerContext ctx,
                                      WebSocketFrame frame) {

        // 判断是否是关闭链路的指令
        if (frame instanceof CloseWebSocketFrame) {
            handShaker.close(ctx.channel(),
                    (CloseWebSocketFrame) frame.retain());
            log.info("session closed. sessionId is {}.", ctx.channel().id().asShortText());
            sessions.remove(ctx.channel().id().asShortText());
            return;
        }
        // 判断是否是Ping消息 协议级别的
        if (frame instanceof PingWebSocketFrame) {
            ctx.channel().write(
                    new PongWebSocketFrame(frame.content().retain()));
            log.info("ws received ping frame.");
            return;
        }
        // 仅支持文本消息，不支持二进制消息
        if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException(String.format(
                    "%s frame types not supported", frame.getClass().getName()));
        }

        // 返回应答消息
        String request = ((TextWebSocketFrame) frame).text();

        if ("ping".equals(request)) {
            ctx.channel().write(new TextWebSocketFrame("pong"));
            return;
        }

        log.info("{} receiver {}", ctx.channel(), request);
        ctx.channel().write(
                new TextWebSocketFrame(request
                        + " , 欢迎使用Netty，现在时刻："
                        + DateUtil.now()));
    }

    private static void sendHttpResponse(ChannelHandlerContext ctx,
                                         FullHttpRequest req, FullHttpResponse res) {
        // 返回应答给客户端
        if (res.getStatus().code() != 200) {
            ByteBuf buf = Unpooled.copiedBuffer(res.getStatus().toString(),
                    CharsetUtil.UTF_8);
            res.content().writeBytes(buf);
            buf.release();
            HttpHeaders.setContentLength(res, res.content().readableBytes());
        }

        // 如果是非Keep-Alive，关闭连接
        ChannelFuture f = ctx.channel().writeAndFlush(res);
        if (!HttpHeaders.isKeepAlive(req) || res.getStatus().code() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }


}
