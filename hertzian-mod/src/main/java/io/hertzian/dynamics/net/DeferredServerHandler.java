package io.hertzian.dynamics.net;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

/**
 * Wraps a server bound IMessageHandler so its body runs on the server
 * main thread instead of the netty thread SimpleNetworkWrapper invokes
 * it on. The delegate is the real, unchanged handler; this wrapper only
 * defers the call through {@link ServerThreadQueue}.
 *
 * <p>
 * Not final. SimpleNetworkWrapper names each netty pipeline entry by
 * the handler's concrete class name, so registering two instances of one
 * wrapper class collides with "Duplicate handler name". Each registration
 * site therefore subclasses this anonymously ({@code new DeferredServerHandler<>(h) {}}),
 * which gives every entry a distinct synthetic class name while sharing
 * this one deferral body.
 */
public class DeferredServerHandler<REQ extends IMessage> implements IMessageHandler<REQ, IMessage> {

    private final IMessageHandler<REQ, IMessage> delegate;

    public DeferredServerHandler(IMessageHandler<REQ, IMessage> delegate) {
        this.delegate = delegate;
    }

    @Override
    public IMessage onMessage(REQ message, MessageContext ctx) {
        ServerThreadQueue.enqueue(() -> delegate.onMessage(message, ctx));
        return null;
    }
}
