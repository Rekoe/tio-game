package com.onemena.game.socket.handler;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.nutz.log.Log;
import org.nutz.log.Logs;
import org.tio.core.ChannelContext;
import org.tio.core.GroupContext;
import org.tio.core.exception.AioDecodeException;
import org.tio.core.intf.Packet;
import org.tio.server.intf.ServerAioHandler;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.MessageLite;
import com.google.protobuf.MessageLiteOrBuilder;
import com.onemena.game.proto.Frame;
import com.onemena.game.utils.ClassUtil;

public abstract class AbstractProtoBufHandler implements ServerAioHandler {

	private final Log log = Logs.get();

	protected final ConcurrentMap<String, Method> methodCache = new ConcurrentHashMap<>();

	private final MessageLite prototype = Frame.getDefaultInstance();
	
	public abstract String getMessageClassPath();
	
	public void init(){
		List<Class<?>> classes = ClassUtil.getAllClassBySubClass(MessageLite.class, true, getMessageClassPath());
		classes.stream().filter(protoClass -> !Objects.equals(protoClass, Frame.class)).forEach(protoClass -> {
			try {
				methodCache.put(protoClass.getSimpleName(), protoClass.getMethod("parseFrom", ByteString.class));
			} catch (NoSuchMethodException e) {
				throw new RuntimeException(e);
			}
		});
	}
	
	@Override
	public Packet decode(ByteBuffer buffer, int limit, int position, int readableLength, ChannelContext channelContext) throws AioDecodeException {
		CodedInputStream cis = CodedInputStream.newInstance(buffer.array(), buffer.position() + buffer.arrayOffset(), buffer.remaining());
		try {
			cis.pushLimit(cis.readRawVarint32());
			Frame msg = (Frame) prototype.getParserForType().parseFrom(cis);
			cis.checkLastTagWas(0);
			String type = msg.getMessageType();
			ByteString body = msg.getPayload();
			Method method = methodCache.get(type);
			if (method == null) {
				throw new RuntimeException("unknown Message type :" + type);
			}
			MessageLite messageLite = (MessageLite) method.invoke(null, body);
			return new MessagePacket(messageLite);
		} catch (Exception e) {
			log.error(e);
		} finally {
			buffer.position(buffer.position() + cis.getTotalBytesRead());
		}
		return null;
	}

	@Override
	public ByteBuffer encode(Packet packet, GroupContext groupContext, ChannelContext channelContext) {
		MessageLite messageLite = ((MessagePacket) packet).getMessageLite();
		Frame frame = Frame.newBuilder().setMessageType(messageLite.getClass().getSimpleName()).setPayload(messageLite.toByteString()).build();
		int serializedSize = frame.getSerializedSize();
		int vintSize = CodedOutputStream.computeUInt32SizeNoTag(serializedSize);
		byte[] buffer = new byte[serializedSize + vintSize];
		CodedOutputStream cos = CodedOutputStream.newInstance(buffer);
		try {
			cos.writeMessageNoTag(frame);
			cos.flush();
			cos.checkNoSpaceLeft();
		} catch (IOException e) {
			log.error(e);
		}
		return ByteBuffer.wrap(buffer);
	}

	public byte[] encodeNoLengthPrefix(MessageLiteOrBuilder msg) {
		if (msg instanceof MessageLite) {
			return ((MessageLite) msg).toByteArray();
		}
		if (msg instanceof MessageLite.Builder) {
			return ((MessageLite.Builder) msg).build().toByteArray();
		}
		return null;
	}

	public abstract void doHandler(MessageLite packet, ChannelContext channelContext) throws Exception;

	@Override
	public void handler(Packet packet, ChannelContext channelContext) throws Exception {
		MessageLite messageLite = ((MessagePacket) packet).getMessageLite();
		doHandler(messageLite, channelContext);
	}

}
