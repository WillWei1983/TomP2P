package net.tomp2p.holep;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.tomp2p.connection.ChannelCreator;
import net.tomp2p.connection.HolePunchInitiator;
import net.tomp2p.futures.BaseFutureAdapter;
import net.tomp2p.futures.FutureChannelCreator;
import net.tomp2p.futures.FutureDone;
import net.tomp2p.futures.FutureResponse;
import net.tomp2p.message.Buffer;
import net.tomp2p.message.Message;
import net.tomp2p.message.Message.Type;
import net.tomp2p.p2p.Peer;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.peers.PeerSocketAddress;
import net.tomp2p.rpc.RPC;
import net.tomp2p.rpc.RPC.Commands;
import net.tomp2p.utils.Pair;
import net.tomp2p.utils.Utils;

public class HolePuncher implements IPunchHole {

	// these fields are needed from both procedures: initiation and reply
	private static final boolean BROADCAST_VALUE = HolePunchInitiator.BROADCAST;
	private static final boolean FIRE_AND_FORGET_VALUE = false;
	private final Peer peer;
	private final int numberOfHoles;
	private final int idleUDPSeconds;
	private boolean initiator = false;
	private final Message originalMessage;
	
	// these fields are needed for the reply procedure
	private List<ChannelFuture> channelFutures = new ArrayList<ChannelFuture>();
	private Message replyMessage;
	private FutureResponse frResponse;
	private PeerAddress originalSender;
	final List<Pair<Integer, Integer>> portMappings = new ArrayList<Pair<Integer, Integer>>();

	// these fields are needed for the initiation procedure
	private FutureDone<FutureResponse> fDone;

	public HolePuncher(final Peer peer, final int numberOfHoles, final int idleUDPSeconds, final Message originalMessage) {
		this.peer = peer;
		this.numberOfHoles = numberOfHoles;
		this.idleUDPSeconds = idleUDPSeconds;
		this.originalMessage = originalMessage;
	}

	public FutureDone<FutureResponse> initiateHolePunch(final SimpleChannelInboundHandler<Message> originalHandler,
			final ChannelCreator originalChannelCreator, final FutureResponse originalFutureResponse) {
		this.initiator = true;
		this.fDone = new FutureDone<FutureResponse>();

		FutureDone<List<ChannelFuture>> fDoneChannelFutures = createChannelFutures(originalFutureResponse,
				prepareHandlers(originalFutureResponse));
		fDoneChannelFutures.addListener(new BaseFutureAdapter<FutureDone<List<ChannelFuture>>>() {

			@Override
			public void operationComplete(FutureDone<List<ChannelFuture>> future) throws Exception {
				if (future.isSuccess()) {
					List<ChannelFuture> futures = future.object();
					peer.connectionBean()
							.sender()
							.sendUDP(createHolePHandler(futures, originalFutureResponse), originalFutureResponse,
									createHolePMessage1(futures), originalChannelCreator, idleUDPSeconds, BROADCAST_VALUE);
				} else {
					fDone.failed("No ChannelFuture could be created!");
				}
			}
		});
		return fDone;
	}

	private SimpleChannelInboundHandler<Message> createHolePHandler(final List<ChannelFuture> futures,
			final FutureResponse originalFutureResponse) {
		SimpleChannelInboundHandler<Message> holePHandler = new SimpleChannelInboundHandler<Message>() {

			@Override
			protected void channelRead0(ChannelHandlerContext ctx, Message msg) throws Exception {
				if (checkReplyValues(msg)) {
					for (int i = 0; i < msg.intList().size(); i++) {
						final int localPort = msg.intList().get(i);
						i++;
						final int remotePort = msg.intList().get(i);
						final ChannelFuture channelFuture = extractChannelFuture(futures, localPort);
						if (channelFuture == null) {
							// TODO jwa handleFail
						}
						Message sendMessage = createSendOriginalMessage(localPort, remotePort);
						peer.connectionBean().sender().afterConnect(originalFutureResponse, sendMessage, channelFuture, false);
					}
				}
			}
		};

		return holePHandler;
	}

	private ChannelFuture extractChannelFuture(final List<ChannelFuture> futures, int localPort) {
		for (ChannelFuture future : futures) {
			if (future.channel().localAddress() != null) {
				InetSocketAddress inetSocketAddress = (InetSocketAddress) future.channel().localAddress();
				if (inetSocketAddress.getPort() == localPort)
					;
				return future;
			}
		}
		return null;
	}

	private List<Map<String, Pair<EventExecutorGroup, ChannelHandler>>> prepareHandlers(final FutureResponse originalFutureResponse) {
		List<Map<String, Pair<EventExecutorGroup, ChannelHandler>>> handlerList = new ArrayList<Map<String, Pair<EventExecutorGroup, ChannelHandler>>>(
				numberOfHoles);
		for (int i = 0; i < numberOfHoles; i++) {
			final Map<String, Pair<EventExecutorGroup, ChannelHandler>> handlers;
			if (initiator) {
				final SimpleChannelInboundHandler<Message> inboundHandler = createAfterHolePHandler();
				handlers = peer.connectionBean().sender().configureHandlers(inboundHandler, originalFutureResponse, idleUDPSeconds, false);
			} else {
				handlers = peer.connectionBean().sender()
						.configureHandlers(peer.connectionBean().dispatcher(), originalFutureResponse, idleUDPSeconds, false);
			}
			handlerList.add(handlers);
		}

		return handlerList;
	}

	private SimpleChannelInboundHandler<Message> createAfterHolePHandler() {
		final SimpleChannelInboundHandler<Message> inboundHandler = new SimpleChannelInboundHandler<Message>() {

			@Override
			protected void channelRead0(ChannelHandlerContext ctx, Message msg) throws Exception {
				if (Message.Type.OK == msg.type() && originalMessage.command() == msg.command()) {
					System.err.println("IT FINALLY WORKED!");
					// fDone.done(msg);
				} else {
					System.err.println("IT DIDN'T WORK YET");
				}
			}
		};
		return inboundHandler;
	}

	private final FutureDone<List<ChannelFuture>> createChannelFutures(final FutureResponse originalFutureResponse,
			final List<Map<String, Pair<EventExecutorGroup, ChannelHandler>>> handlersList) {

		final FutureDone<List<ChannelFuture>> fDoneChannelFutures = new FutureDone<List<ChannelFuture>>();
		final AtomicInteger countDown = new AtomicInteger(numberOfHoles);
		final List<ChannelFuture> channelFutures = new ArrayList<ChannelFuture>();

		for (int i = 0; i < numberOfHoles; i++) {
			final Map<String, Pair<EventExecutorGroup, ChannelHandler>> handlers = handlersList.get(i);

			FutureChannelCreator fcc = peer.connectionBean().reservation().create(1, 0);
			fcc.addListener(new BaseFutureAdapter<FutureChannelCreator>() {
				@Override
				public void operationComplete(FutureChannelCreator future) throws Exception {
					if (future.isSuccess()) {
						ChannelFuture cF = future.channelCreator().createUDP(BROADCAST_VALUE, handlers, originalFutureResponse, null);
						cF.addListener(new GenericFutureListener<ChannelFuture>() {
							@Override
							public void operationComplete(ChannelFuture future) throws Exception {
								if (future.isSuccess()) {
									channelFutures.add(future);
								} else {
									// TODO jwa do FailMessage
								}
								countDown.decrementAndGet();
								if (countDown.get() == 0) {
									fDoneChannelFutures.done(channelFutures);
								}
							}
						});
					} else {
						countDown.decrementAndGet();
						// TODO jwa do FailMessage
					}
				}
			});
		}
		return fDoneChannelFutures;
	}

	private boolean checkReplyValues(Message msg) {
		boolean ok = false;
		if (msg.command() == Commands.HOLEP.getNr() && msg.type() == Type.OK) {
			// the list with the ports should never be null or Empty
			if (!(msg.intList() == null || msg.intList().isEmpty())) {
				final int rawNumberOfHoles = msg.intList().size();
				// the number of the pairs of port must be even!
				if ((rawNumberOfHoles % 2) == 0) {
					ok = true;
				} else {
					handleFail("The number of ports in IntList was odd! This should never happen");
				}
			} else {
				handleFail("IntList in replyMessage was null or Empty! No ports available!!!!");
			}
		} else {
			handleFail("Could not acquire a connection via hole punching, got: " + msg);
		}
		return ok;
	}

	/**
	 * This method duplicates the original {@link Message} multiple times. This
	 * is needed, because the {@link Buffer} can only be read once.
	 * 
	 * @param originalMessage
	 * @param localPort
	 * @param remotePort
	 * @return
	 */
	private Message createSendOriginalMessage(final int localPort, final int remotePort) {
		Message sendMessage = new Message();
		PeerAddress sender = originalMessage.sender().changePorts(-1, localPort).changeFirewalledTCP(false).changeFirewalledUDP(false)
				.changeRelayed(false);
		PeerAddress recipient = originalMessage.recipient().changePorts(-1, remotePort).changeFirewalledTCP(false)
				.changeFirewalledUDP(false).changeRelayed(false);
		sendMessage.recipient(recipient);
		sendMessage.sender(sender);
		sendMessage.version(originalMessage.version());
		sendMessage.command(originalMessage.command());
		sendMessage.type(originalMessage.type());
		sendMessage.udp(true);
		for (Buffer buf : originalMessage.bufferList()) {
			sendMessage.buffer(new Buffer(buf.buffer().duplicate()));
		}
		return sendMessage;
	}

	/**
	 * This method creates the initial {@link Message} with {@link Commands}
	 * .HOLEP and {@link Type}.REQUEST_1. This {@link Message} will be forwarded
	 * to the rendez-vous server (a relay of the remote peer) and initiate the
	 * hole punching procedure on the other peer.
	 * 
	 * @param message
	 * @param channelCreator
	 * @return holePMessage
	 */
	private Message createHolePMessage1(List<ChannelFuture> channelFutures) {
		PeerSocketAddress socketAddress = Utils.extractRandomRelay(originalMessage);

		// we need to make a copy of the original Message
		Message holePMessage = new Message();

		// socketInfoMessage.messageId(message.messageId());
		PeerAddress recipient = originalMessage.recipient().changeAddress(socketAddress.inetAddress())
				.changePorts(socketAddress.tcpPort(), socketAddress.udpPort()).changeRelayed(false);
		holePMessage.recipient(recipient);
		holePMessage.sender(originalMessage.sender());
		holePMessage.version(originalMessage.version());
		holePMessage.udp(true);
		holePMessage.command(RPC.Commands.HOLEP.getNr());
		holePMessage.type(Message.Type.REQUEST_1);

		// TODO jwa --> create something like a configClass or file where the
		// number of holes in the firewall can be specified.
		for (int i = 0; i < channelFutures.size(); i++) {
			InetSocketAddress inetSocketAddress = (InetSocketAddress) channelFutures.get(i).channel().localAddress();
			holePMessage.intValue(inetSocketAddress.getPort());
		}

		return holePMessage;
	}

	private void handleFail(String failMessage) {
		fDone.failed(failMessage);
	}

	public FutureDone<Message> replyHolePunch() {
		this.originalSender = (PeerAddress) originalMessage.neighborsSetList().get(0).neighbors().toArray()[0];
		final FutureDone<Message> replyMessageFuture = new FutureDone<Message>();
		frResponse = new FutureResponse(originalMessage);
		final HolePuncher thisInstance = this;

		FutureDone<List<ChannelFuture>> rmfChannelFutures = createChannelFutures(frResponse, prepareHandlers(frResponse));
		rmfChannelFutures.addListener(new BaseFutureAdapter<FutureDone<List<ChannelFuture>>>() {

			@Override
			public void operationComplete(FutureDone<List<ChannelFuture>> future) throws Exception {
				if (future.isSuccess()) {
					channelFutures = future.object();
					doPortMappings();
					replyMessage = createReplyMessage();
					HolePunchScheduler.instance().addHolePuncher(10, thisInstance);
					replyMessageFuture.done(replyMessage);
				} else {
					replyMessageFuture.failed("No ChannelFuture could be created!");
				}
			}

		});
		return replyMessageFuture;
	}
	
	private void doPortMappings() {
		for (int i = 0; i < channelFutures.size(); i++) {
			InetSocketAddress socket = (InetSocketAddress) channelFutures.get(i).channel().localAddress();
			portMappings.add(new Pair<Integer, Integer>(originalMessage.intList().get(i), socket.getPort()));
		}
	}

	public void tryConnect() {
		for (int i = 0; i < channelFutures.size(); i++) {
			Message dummyMessage = createDummyMessage(i);
			FutureResponse futureResponse = new FutureResponse(dummyMessage);
			peer.connectionBean().sender().afterConnect(futureResponse, dummyMessage, channelFutures.get(i), FIRE_AND_FORGET_VALUE);
			peer.peerBean().peerMap().peerFound(originalSender, originalSender, null);
		}
	}

	public Message createDummyMessage(int i) {
		Message dummyMessage = new Message();
		final int remotePort = portMappings.get(i).element0();
		final int localPort = portMappings.get(i).element1();
		PeerAddress recipient = originalSender.changeFirewalledUDP(false).changeRelayed(false)
				.changePorts(-1, remotePort);
		PeerAddress sender = peer.peerAddress().changePorts(-1, localPort);
		dummyMessage.recipient(recipient);
		dummyMessage.command(Commands.HOLEP.getNr());
		dummyMessage.type(Type.REQUEST_3);
		dummyMessage.sender(sender);
		return dummyMessage;
	}

	private Message createReplyMessage() {
//		Message replyMessage = peer.connectionBean().dispatcher().createMessage(originalSender, Commands.HOLEP.getNr(), Message.Type.OK);
		Message replyMessage = new Message();
		replyMessage.recipient(originalMessage.sender());
		replyMessage.command(Commands.HOLEP.getNr());
		replyMessage.type(Message.Type.OK);
		replyMessage.messageId(originalMessage.messageId());
		for (Pair<Integer, Integer> pair : portMappings) {
			if (!(pair == null || pair.isEmpty() || pair.element0() == null || pair.element1() == null)) {
				replyMessage.intValue(pair.element0());
				replyMessage.intValue(pair.element1());
			}
		}
		return replyMessage;
	}
}