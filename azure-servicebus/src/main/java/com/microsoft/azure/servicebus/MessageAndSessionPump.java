package com.microsoft.azure.servicebus;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

import com.microsoft.azure.servicebus.primitives.MessageLockLostException;
import com.microsoft.azure.servicebus.primitives.MessagingFactory;
import com.microsoft.azure.servicebus.primitives.OperationCancelledException;
import com.microsoft.azure.servicebus.primitives.ServiceBusException;
import com.microsoft.azure.servicebus.primitives.SessionLockLostException;
import com.microsoft.azure.servicebus.primitives.StringUtil;
import com.microsoft.azure.servicebus.primitives.TimeoutException;
import com.microsoft.azure.servicebus.primitives.Timer;
import com.microsoft.azure.servicebus.primitives.TimerType;

class MessageAndSessionPump extends InitializableEntity implements IMessageAndSessionPump
{
	// Larger value means few receive calls to the internal receiver and better.
	private static final Duration MESSAGE_RECEIVE_TIMEOUT = Duration.ofSeconds(60);
	private static final Duration MINIMUM_MESSAGE_LOCK_VALIDITY = Duration.ofSeconds(4);
	private static final Duration MAXIMUM_RENEW_LOCK_BUFFER = Duration.ofSeconds(10);	
	private static final Duration SLEEP_DURATION_ON_ACCEPT_SESSION_EXCEPTION = Duration.ofMinutes(1);
	
	private final MessagingFactory factory;
	private final String entityPath;
	private final ReceiveMode receiveMode;
	private IMessageReceiver innerReceiver;
	
	private boolean handlerRegistered = false;
	private IMessageHandler messageHandler;
	private ISessionHandler sessionHandler;
	private MessageHandlerOptions messageHandlerOptions;
	private SessionHandlerOptions sessionHandlerOptions;
	
	public MessageAndSessionPump(MessagingFactory factory, String entityPath, ReceiveMode receiveMode)
	{
		super(StringUtil.getShortRandomString(), null);
		this.factory = factory;
		this.entityPath = entityPath;
		this.receiveMode = receiveMode;
	}

	@Override
	public void registerMessageHandler(IMessageHandler handler) throws InterruptedException, ServiceBusException{
		this.registerMessageHandler(handler, new MessageHandlerOptions());		
	}

	@Override
	public void registerMessageHandler(IMessageHandler handler, MessageHandlerOptions handlerOptions) throws InterruptedException, ServiceBusException{
		this.setHandlerRegistered();
		this.messageHandler = handler;
		this.messageHandlerOptions = handlerOptions;
		
		this.innerReceiver =  ClientFactory.createMessageReceiverFromEntityPath(this.factory, this.entityPath, this.receiveMode);
				
		for(int i=0; i<handlerOptions.getMaxConcurrentCalls(); i++)
		{
			this.receiveAndPumpMessage();
		}
	}

	@Override
	public void registerSessionHandler(ISessionHandler handler) throws InterruptedException, ServiceBusException
	{
		this.registerSessionHandler(handler, new SessionHandlerOptions());		
	}

	@Override
	public void registerSessionHandler(ISessionHandler handler, SessionHandlerOptions handlerOptions) throws InterruptedException, ServiceBusException
	{
		this.setHandlerRegistered();
		this.sessionHandler = handler;
		this.sessionHandlerOptions = handlerOptions;
		
		for(int i=0; i<handlerOptions.getMaxConcurrentSessions(); i++)
		{
			this.acceptSessionsAndPumpMessage();
		}
	}
	
	private synchronized void setHandlerRegistered()
	{
		this.throwIfClosed(null);
		
		// Only one handler is allowed to be registered per client
		if(this.handlerRegistered)
		{
			throw new UnsupportedOperationException("MessageHandler or SessionHandler already registered.");
		}
		
		this.handlerRegistered = true;
	}

	private void receiveAndPumpMessage()
	{
		if(!this.getIsClosingOrClosed())
		{
			CompletableFuture<IMessage> receiveMessageFuture = this.innerReceiver.receiveAsync(MessageAndSessionPump.MESSAGE_RECEIVE_TIMEOUT);
			receiveMessageFuture.handleAsync((message, receiveEx) -> {
				if(receiveEx != null)
				{
					this.messageHandler.notifyException(receiveEx, ExceptionPhase.RECEIVE);
					this.receiveAndPumpMessage();
				}
				else
				{
					if(message == null)
					{
						this.receiveAndPumpMessage();
					}
					else
					{
						// Start renew lock loop
						final MessgeRenewLockLoop renewLockLoop;													
						if(this.innerReceiver.getReceiveMode() == ReceiveMode.PeekLock)
						{
							Instant stopRenewMessageLockAt = Instant.now().plus(this.messageHandlerOptions.getMaxAutoRenewDuration());
							renewLockLoop = new MessgeRenewLockLoop(this.innerReceiver, new HandlerWrapper(this.messageHandler), message, stopRenewMessageLockAt);
							renewLockLoop.startLoop();
						}
						else
						{
							renewLockLoop = null;
						}
						
						CompletableFuture<Void> onMessageFuture;
						try
						{
							onMessageFuture = this.messageHandler.onMessageAsync(message);							
						}
						catch(Exception onMessageSyncEx)
						{
							onMessageFuture = new CompletableFuture<Void>();
							onMessageFuture.completeExceptionally(onMessageSyncEx);							
						}						
						
						onMessageFuture.handleAsync((v, onMessageEx) -> {
							if(onMessageEx != null)
							{
								this.messageHandler.notifyException(onMessageEx, ExceptionPhase.USERCALLBACK);
							}
							if(this.innerReceiver.getReceiveMode() == ReceiveMode.PeekLock)
							{
								if(renewLockLoop != null)
								{
									renewLockLoop.cancelLoop();
								}
								CompletableFuture<Void> updateDispositionFuture;
								ExceptionPhase dispositionPhase;
								if(onMessageEx == null)
								{
									// Complete message
									dispositionPhase = ExceptionPhase.COMPLETE;
									if(this.messageHandlerOptions.isAutoComplete())
									{																				
										updateDispositionFuture = this.innerReceiver.completeAsync(message.getLockToken());
									}
									else
									{
										updateDispositionFuture = CompletableFuture.completedFuture(null);
									}
								}
								else
								{									
									// Abandon message
									dispositionPhase = ExceptionPhase.ABANDON;
									updateDispositionFuture = this.innerReceiver.abandonAsync(message.getLockToken());
								}
								
								updateDispositionFuture.handleAsync((u, updateDispositionEx) -> {
									if(updateDispositionEx != null)
									{
										System.out.println(message.getMessageId());
										this.messageHandler.notifyException(updateDispositionEx, dispositionPhase);
									}
									this.receiveAndPumpMessage();
									return null;
								});
							}
							else
							{
								this.receiveAndPumpMessage();
							}
							
							return null;
						});
					}
					
				}
				
				return null;
			});
		}		
	}
	
	private void acceptSessionsAndPumpMessage()
	{
		if(!this.getIsClosingOrClosed())
		{			
			CompletableFuture<IMessageSession> acceptSessionFuture = ClientFactory.acceptSessionFromEntityPathAsync(this.factory, this.entityPath, null, this.receiveMode);
			acceptSessionFuture.handleAsync((session, acceptSessionEx) -> {
				if(acceptSessionEx != null)
				{
					if(!(acceptSessionEx instanceof TimeoutException))
					{
						// Timeout exception means no session available.. it is expected so no need to notify client
						this.sessionHandler.notifyException(acceptSessionEx, ExceptionPhase.ACCEPTSESSION);
					}
					
					if(!(acceptSessionEx instanceof OperationCancelledException))
					{
						// don't retry if OperationCancelled by service.. may be entity itself is deleted
						// In case of any other exception, sleep and retry
						Timer.schedule(() -> {MessageAndSessionPump.this.acceptSessionsAndPumpMessage();}, SLEEP_DURATION_ON_ACCEPT_SESSION_EXCEPTION, TimerType.OneTimeRun);
					}					
				}
				else
				{
					// Received a session.. Now pump messages..
					SessionRenewLockLoop sessionRenewLockLoop = new SessionRenewLockLoop(session, new HandlerWrapper(this.sessionHandler));
					sessionRenewLockLoop.startLoop();
					SessionTracker sessionTracker = new SessionTracker(this, session, sessionRenewLockLoop);
					for(int i=0; i<this.sessionHandlerOptions.getMaxConcurrentCallsPerSession(); i++)
					{
						this.receiveFromSessionAndPumpMessage(sessionTracker);
					}
					
				}
				
				return null;
			});
		}
	}
	
	private void receiveFromSessionAndPumpMessage(SessionTracker sessionTracker)
	{
		if(!this.getIsClosingOrClosed())
		{
			IMessageSession session = sessionTracker.getSession();
			CompletableFuture<IMessage> receiverFuture = session.receiveAsync(MessageAndSessionPump.MESSAGE_RECEIVE_TIMEOUT);
			receiverFuture.handleAsync((message, receiveEx) -> {
				if(receiveEx != null)
				{
					this.sessionHandler.notifyException(receiveEx, ExceptionPhase.RECEIVE);
					sessionTracker.shouldRetryOnNoMessageOrException().thenAcceptAsync((shouldRetry) -> {
						if(shouldRetry)
						{
							this.receiveFromSessionAndPumpMessage(sessionTracker);
						}
					});
				}
				else
				{
					if(message == null)
					{
						sessionTracker.shouldRetryOnNoMessageOrException().thenAcceptAsync((shouldRetry) -> {
							if(shouldRetry)
							{
								this.receiveFromSessionAndPumpMessage(sessionTracker);
							}
						});						
					}
					else
					{
						sessionTracker.notifyMessageReceived();
						// There is no need to renew message locks as session messages are locked for a day
						ScheduledFuture<?> renewCancelTimer = Timer.schedule(() -> {
							sessionTracker.sessionRenewLockLoop.cancelLoop();},
							this.sessionHandlerOptions.getMaxAutoRenewDuration(),
							TimerType.OneTimeRun);
						CompletableFuture<Void> onMessageFuture;
						try
						{
							onMessageFuture = this.sessionHandler.onMessageAsync(session, message);
							
						}
						catch(Exception onMessageSyncEx)
						{
							onMessageFuture = new CompletableFuture<Void>();
							onMessageFuture.completeExceptionally(onMessageSyncEx);
						}						
						
						onMessageFuture.handleAsync((v, onMessageEx) -> {
							renewCancelTimer.cancel(true);
							if(onMessageEx != null)
							{
								this.sessionHandler.notifyException(onMessageEx, ExceptionPhase.USERCALLBACK);
							}
							if(this.receiveMode == ReceiveMode.PeekLock)
							{								
								CompletableFuture<Void> updateDispositionFuture;
								ExceptionPhase dispositionPhase;
								if(onMessageEx == null)
								{
									// Complete message
									dispositionPhase = ExceptionPhase.COMPLETE;
									if(this.sessionHandlerOptions.isAutoComplete())
									{																				
										updateDispositionFuture = session.completeAsync(message.getLockToken());
									}
									else
									{
										updateDispositionFuture = CompletableFuture.completedFuture(null);
									}									
								}
								else
								{									
									// Abandon message
									dispositionPhase = ExceptionPhase.ABANDON;
									updateDispositionFuture = session.abandonAsync(message.getLockToken());
								}
								
								updateDispositionFuture.handleAsync((u, updateDispositionEx) -> {
									if(updateDispositionEx != null)
									{
										this.sessionHandler.notifyException(updateDispositionEx, dispositionPhase);
									}
									this.receiveFromSessionAndPumpMessage(sessionTracker);
									return null;
								});
							}
							else
							{
								this.receiveFromSessionAndPumpMessage(sessionTracker);
							}
							
							return null;
						});
					}
					
				}
				
				return null;
			});
		}		
	}

	@Override
	CompletableFuture<Void> initializeAsync(){
		return CompletableFuture.completedFuture(null);
	}

	@Override
	protected CompletableFuture<Void> onClose() {
		return this.innerReceiver == null ? CompletableFuture.completedFuture(null) : this.innerReceiver.closeAsync();
	}
	
	private static class SessionTracker
	{
		private final int numberReceivingThreads;
		private final IMessageSession session;
		private final MessageAndSessionPump messageAndSessionPump;
		private final SessionRenewLockLoop sessionRenewLockLoop;
		private int waitingRetryThreads;
		private CompletableFuture<Boolean> retryFuture;		
		 
		SessionTracker(MessageAndSessionPump messageAndSessionPump, IMessageSession session, SessionRenewLockLoop sessionRenewLockLoop)
		{
			this.messageAndSessionPump = messageAndSessionPump;
			this.session = session;
			this.sessionRenewLockLoop = sessionRenewLockLoop;
			this.numberReceivingThreads = messageAndSessionPump.sessionHandlerOptions.getMaxConcurrentCallsPerSession();
			this.waitingRetryThreads = 0;
		}
		
		public IMessageSession getSession()
		{
			return this.session;
		}
		
		synchronized void notifyMessageReceived()
		{
			if(this.retryFuture != null && !this.retryFuture.isDone())
			{
				this.waitingRetryThreads = 0;
				this.retryFuture.complete(true);		
			}
		}
		
		synchronized CompletableFuture<Boolean> shouldRetryOnNoMessageOrException()
		{			
			if(this.retryFuture == null || this.retryFuture.isDone())
			{
				this.retryFuture = new CompletableFuture<Boolean>();
			}
			this.waitingRetryThreads++;
			if(this.waitingRetryThreads == this.numberReceivingThreads)
			{
				this.retryFuture.complete(false);
				
				// close current session and accept another session
				ScheduledFuture<?> renewCancelTimer = Timer.schedule(() -> {
					SessionTracker.this.sessionRenewLockLoop.cancelLoop();},
					this.messageAndSessionPump.sessionHandlerOptions.getMaxAutoRenewDuration(),
					TimerType.OneTimeRun);
				CompletableFuture<Void> onCloseFuture;
				try
				{
					onCloseFuture = this.messageAndSessionPump.sessionHandler.OnCloseSessionAsync(session);					
				}
				catch(Exception onCloseSyncEx)
				{
					onCloseFuture = new CompletableFuture<Void>();
					onCloseFuture.completeExceptionally(onCloseSyncEx);
				}			
				
				onCloseFuture.handleAsync((v, onCloseEx) -> {
					renewCancelTimer.cancel(true);
					if(onCloseEx != null)
					{
						this.messageAndSessionPump.sessionHandler.notifyException(onCloseEx, ExceptionPhase.USERCALLBACK);
					}
					
					this.sessionRenewLockLoop.cancelLoop();
					this.session.closeAsync().handleAsync((z, closeEx) ->
					{
						if(closeEx != null)
						{
							this.messageAndSessionPump.sessionHandler.notifyException(closeEx, ExceptionPhase.SESSIONCLOSE);
						}
						
						this.messageAndSessionPump.acceptSessionsAndPumpMessage();
						return null;
					});
					return null;
				});
				
			}
			
			return this.retryFuture;
		}		
	}
	
	private static class HandlerWrapper
	{
		private IMessageHandler messageHandler = null;
		private ISessionHandler sessionHandler = null;
		
		HandlerWrapper(IMessageHandler messageHandler)
		{
			this.messageHandler = messageHandler;
		}
		
		HandlerWrapper(ISessionHandler sessionHandler)
		{
			this.sessionHandler = sessionHandler;
		}
		
		void notifyException(Throwable exception, ExceptionPhase phase)
		{
			if(this.messageHandler != null)
			{
				this.messageHandler.notifyException(exception, phase);
			}
			else
			{
				this.sessionHandler.notifyException(exception, phase);
			}
		}		
	}
	
	private abstract static class RenewLockLoop
	{
		private boolean cancelled = false;
		
		protected RenewLockLoop()
		{			
		}
		
		protected abstract void loop();		
		
		protected abstract ScheduledFuture<?> getTimerFuture();
		
		protected boolean isCancelled()
		{
			return this.cancelled;
		}
		
		public void startLoop()
		{
			this.loop();
		}
		
		public void cancelLoop()
		{
			if(!this.cancelled)
			{
				this.cancelled = true;
				ScheduledFuture<?> timerFuture = this.getTimerFuture();
				if(timerFuture != null && !timerFuture.isDone())
				{
					timerFuture.cancel(true);
				}
			}			
		}
		
		protected static Duration getNextRenewInterval(Instant lockedUntilUtc)
		{			
			Duration remainingTime = Duration.between(Instant.now(), lockedUntilUtc);
			if(remainingTime.isNegative())
			{
				// Lock likely expired. May be there is clock skew. Assume some minimum time
				remainingTime = MessageAndSessionPump.MINIMUM_MESSAGE_LOCK_VALIDITY;
			}
			
			Duration buffer = remainingTime.dividedBy(2).compareTo(MAXIMUM_RENEW_LOCK_BUFFER) > 0 ? MAXIMUM_RENEW_LOCK_BUFFER : remainingTime.dividedBy(2);	
			return remainingTime.minus(buffer);		
		}
	}
	
	private static class MessgeRenewLockLoop extends RenewLockLoop
	{
		private IMessageReceiver innerReceiver;
		private HandlerWrapper handlerWrapper;
		private IMessage message;
		private Instant stopRenewalAt;
		ScheduledFuture<?> timerFuture;
		
		MessgeRenewLockLoop(IMessageReceiver innerReceiver, HandlerWrapper handlerWrapper, IMessage message, Instant stopRenewalAt)
		{
			super();
			this.innerReceiver = innerReceiver;
			this.handlerWrapper = handlerWrapper;
			this.message = message;
			this.stopRenewalAt = stopRenewalAt;			
		}
		
		@Override
		protected ScheduledFuture<?> getTimerFuture()
		{
			return this.timerFuture;
		}
		
		@Override
		protected void loop()
		{
			if(!this.isCancelled())
			{
				Duration renewInterval = this.getNextRenewInterval();
				if(renewInterval != null && !renewInterval.isNegative())
				{
					this.timerFuture = Timer.schedule(() -> {
						this.innerReceiver.renewMessageLockAsync(message).handleAsync((v, renewLockEx) ->
						{
							if(renewLockEx != null)
							{
								this.handlerWrapper.notifyException(renewLockEx, ExceptionPhase.RENEWMESSAGELOCK);
								if(!(renewLockEx instanceof MessageLockLostException || renewLockEx instanceof OperationCancelledException))
								{
									this.loop();
								}
							}
							else
							{
								this.loop();
							}
							
							
							return null;
						});
					}, renewInterval, TimerType.OneTimeRun);
				}
			}
		}		
		
		private Duration getNextRenewInterval()
		{			
			if(this.message.getLockedUntilUtc().isBefore(stopRenewalAt))
			{
				return RenewLockLoop.getNextRenewInterval(this.message.getLockedUntilUtc());
			}
			else
			{
				return null;
			}			
		}
	}
	
	private static class SessionRenewLockLoop extends RenewLockLoop
	{
		private IMessageSession session;
		private HandlerWrapper handlerWrapper;
		ScheduledFuture<?> timerFuture;
		
		SessionRenewLockLoop(IMessageSession session, HandlerWrapper handlerWrapper)
		{
			super();
			this.session = session;
			this.handlerWrapper = handlerWrapper;			
		}
		
		@Override
		protected ScheduledFuture<?> getTimerFuture()
		{
			return this.timerFuture;
		}
		
		@Override
		protected void loop()
		{
			if(!this.isCancelled())
			{
				Duration renewInterval = RenewLockLoop.getNextRenewInterval(this.session.getLockedUntilUtc());
				if(renewInterval != null && !renewInterval.isNegative())
				{
					this.timerFuture = Timer.schedule(() -> {
						this.session.renewLockAsync().handleAsync((v, renewLockEx) ->
						{
							if(renewLockEx != null)
							{
								this.handlerWrapper.notifyException(renewLockEx, ExceptionPhase.RENEWSESSIONLOCK);
								if(!(renewLockEx instanceof SessionLockLostException || renewLockEx instanceof OperationCancelledException))
								{
									this.loop();
								}
							}
							else
							{
								this.loop();
							}
							
							
							return null;
						});
					}, renewInterval, TimerType.OneTimeRun);
				}
			}
		}
	}

	@Override
	public void abandon(UUID lockToken) throws InterruptedException, ServiceBusException {
		this.checkInnerReceiveCreated();
		this.innerReceiver.abandon(lockToken);
	}

	@Override
	public void abandon(UUID lockToken, Map<String, Object> propertiesToModify) throws InterruptedException, ServiceBusException {
		this.checkInnerReceiveCreated();
		this.innerReceiver.abandon(lockToken, propertiesToModify);		
	}

	@Override
	public CompletableFuture<Void> abandonAsync(UUID lockToken) {
		this.checkInnerReceiveCreated();
		return this.innerReceiver.abandonAsync(lockToken);
	}

	@Override
	public CompletableFuture<Void> abandonAsync(UUID lockToken, Map<String, Object> propertiesToModify) {
		this.checkInnerReceiveCreated();
		return this.innerReceiver.abandonAsync(lockToken, propertiesToModify);
	}

	@Override
	public void complete(UUID lockToken) throws InterruptedException, ServiceBusException {
		this.checkInnerReceiveCreated();
		this.innerReceiver.complete(lockToken);
	}

	@Override
	public CompletableFuture<Void> completeAsync(UUID lockToken) {
		this.checkInnerReceiveCreated();
		return this.innerReceiver.completeAsync(lockToken);
	}

	@Override
	public void defer(UUID lockToken) throws InterruptedException, ServiceBusException {
		this.checkInnerReceiveCreated();
		this.innerReceiver.defer(lockToken);
	}

	@Override
	public void defer(UUID lockToken, Map<String, Object> propertiesToModify) throws InterruptedException, ServiceBusException {
		this.checkInnerReceiveCreated();
		this.innerReceiver.defer(lockToken, propertiesToModify);	
	}

	@Override
	public CompletableFuture<Void> deferAsync(UUID lockToken) {
		this.checkInnerReceiveCreated();
		return this.innerReceiver.abandonAsync(lockToken);
	}

	@Override
	public CompletableFuture<Void> deferAsync(UUID lockToken, Map<String, Object> propertiesToModify) {
		this.checkInnerReceiveCreated();
		return this.innerReceiver.abandonAsync(lockToken, propertiesToModify);
	}

	@Override
	public void deadLetter(UUID lockToken) throws InterruptedException, ServiceBusException {
		this.innerReceiver.deadLetter(lockToken);		
	}

	@Override
	public void deadLetter(UUID lockToken, Map<String, Object> propertiesToModify) throws InterruptedException, ServiceBusException {
		this.innerReceiver.deadLetter(lockToken, propertiesToModify);		
	}

	@Override
	public void deadLetter(UUID lockToken, String deadLetterReason, String deadLetterErrorDescription) throws InterruptedException, ServiceBusException {
		this.checkInnerReceiveCreated();
		this.innerReceiver.deadLetter(lockToken, deadLetterReason, deadLetterErrorDescription);		
	}

	@Override
	public void deadLetter(UUID lockToken, String deadLetterReason, String deadLetterErrorDescription, Map<String, Object> propertiesToModify) throws InterruptedException, ServiceBusException {
		this.checkInnerReceiveCreated();
		this.innerReceiver.deadLetter(lockToken, deadLetterReason, deadLetterErrorDescription, propertiesToModify);		
	}

	@Override
	public CompletableFuture<Void> deadLetterAsync(UUID lockToken) {
		this.checkInnerReceiveCreated();
		return this.innerReceiver.deadLetterAsync(lockToken);
	}

	@Override
	public CompletableFuture<Void> deadLetterAsync(UUID lockToken, Map<String, Object> propertiesToModify) {
		this.checkInnerReceiveCreated();
		return this.innerReceiver.deadLetterAsync(lockToken, propertiesToModify);
	}

	@Override
	public CompletableFuture<Void> deadLetterAsync(UUID lockToken, String deadLetterReason,	String deadLetterErrorDescription) {
		this.checkInnerReceiveCreated();
		return this.innerReceiver.deadLetterAsync(lockToken, deadLetterReason, deadLetterErrorDescription);
	}

	@Override
	public CompletableFuture<Void> deadLetterAsync(UUID lockToken, String deadLetterReason, String deadLetterErrorDescription, Map<String, Object> propertiesToModify) {
		this.checkInnerReceiveCreated();
		return this.innerReceiver.deadLetterAsync(lockToken, deadLetterReason, deadLetterErrorDescription, propertiesToModify);
	}
	
	private void checkInnerReceiveCreated()
	{
		if(this.innerReceiver == null)
		{
			throw new UnsupportedOperationException("This operation is not supported on a message received from a session. Use the session to perform the operation.");
		}
	}
}
