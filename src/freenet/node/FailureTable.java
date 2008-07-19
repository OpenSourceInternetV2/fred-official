/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.lang.ref.WeakReference;
import java.util.Vector;

import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.Message;
import freenet.io.comm.NotConnectedException;
import freenet.io.xfer.BlockTransmitter;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.io.xfer.WaitedTooLongException;
import freenet.keys.CHKBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.keys.SSKBlock;
import freenet.support.LRUHashtable;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.OOMHook;
import freenet.support.SerialExecutor;
import freenet.support.io.NativeThread;

// FIXME it is ESSENTIAL that we delete the ULPR data on requestors etc once we have found the key.
// Otherwise it will be much too easy to trace a request if an attacker busts the node afterwards.
// We can use an HMAC or something to authenticate offers.

// LOCKING: Always take the FailureTable lock first if you need both. Take the FailureTableEntry 
// lock only on cheap internal operations.

/**
 * Tracks recently DNFed keys, where they were routed to, what the location was at the time, who requested them.
 * Implements Ultra-Lightweight Persistent Requests: Refuse requests for a key for 10 minutes after it's DNFed 
 * (UNLESS we find a better route for the request), and when it is found, offer it to those who've asked for it
 * in the last hour.
 * @author toad
 */
public class FailureTable implements OOMHook {
	
	/** FailureTableEntry's by key. Note that we push an entry only when sentTime changes. */
	private final LRUHashtable entriesByKey;
	/** BlockOfferList by key */
	private final LRUHashtable blockOfferListByKey;
	private final Node node;
	
	/** Maximum number of keys to track */
	static final int MAX_ENTRIES = 2*1000;
	/** Maximum number of offers to track */
	static final int MAX_OFFERS = 1*1000;
	/** Terminate a request if there was a DNF on the same key less than 10 minutes ago */
	static final int REJECT_TIME = 10*60*1000;
	/** After 1 hour we forget about an entry completely */
	static final int MAX_LIFETIME = 60*60*1000;
	/** Offers expire after 10 minutes */
	static final int OFFER_EXPIRY_TIME = 10*60*1000;
	/** HMAC key for the offer authenticator */
	final byte[] offerAuthenticatorKey;
	/** Clean up old data every 30 minutes to save memory and improve privacy */
	static final int CLEANUP_PERIOD = 30*60*1000;
	
	static boolean logMINOR;
	static boolean logDEBUG;
	
	FailureTable(Node node) {
		entriesByKey = new LRUHashtable();
		blockOfferListByKey = new LRUHashtable();
		this.node = node;
		offerAuthenticatorKey = new byte[32];
		node.random.nextBytes(offerAuthenticatorKey);
		logMINOR = Logger.shouldLog(Logger.MINOR, this);
		logDEBUG = Logger.shouldLog(Logger.DEBUG, this);
		offerExecutor = new SerialExecutor(NativeThread.HIGH_PRIORITY);
		node.ps.queueTimedJob(new FailureTableCleaner(), CLEANUP_PERIOD);
	}
	
	public void start() {
		offerExecutor.start(node.executor, "FailureTable offers executor");
		OOMHandler.addOOMHook(this);
	}
	
	/**
	 * Called when we route to a node and it fails for some reason, but we continue the request.
	 * Normally the timeout will be the time it took to route to that node and wait for its 
	 * response / timeout waiting for its response.
	 * @param key
	 * @param routedTo
	 * @param htl
	 * @param timeout
	 */
	public void onFailed(Key key, PeerNode routedTo, short htl, int timeout) {
		if(!(node.enableULPRDataPropagation || node.enablePerNodeFailureTables)) return;
		long now = System.currentTimeMillis();
		FailureTableEntry entry;
		synchronized(this) {
			entry = (FailureTableEntry) entriesByKey.get(key);
			if(entry == null) {
				entry = new FailureTableEntry(key);
				entriesByKey.push(key, entry);
			} else {
				entriesByKey.push(key, entry);
			}
			trimEntries(now);
		}
		entry.failedTo(routedTo, timeout, now, htl);
	}
	
	public void onFinalFailure(Key key, PeerNode routedTo, short htl, int timeout, PeerNode requestor) {
		if(!(node.enableULPRDataPropagation || node.enablePerNodeFailureTables)) return;
		long now = System.currentTimeMillis();
		FailureTableEntry entry;
		synchronized(this) {
			entry = (FailureTableEntry) entriesByKey.get(key);
			if(entry == null) {
				entry = new FailureTableEntry(key);
				entriesByKey.push(key, entry);
			} else {
				entriesByKey.push(key, entry);
			}
			trimEntries(now);
		}
		if(routedTo != null)
			entry.failedTo(routedTo, timeout, now, htl);
		if(requestor != null)
			entry.addRequestor(requestor, now);
	}
	
	private synchronized void trimEntries(long now) {
		while(entriesByKey.size() > MAX_ENTRIES) {
			entriesByKey.popKey();
		}
	}

	private final class BlockOfferList {
		private BlockOffer[] offers;
		final FailureTableEntry entry;
		
		BlockOfferList(FailureTableEntry entry, BlockOffer offer) {
			this.entry = entry;
			this.offers = new BlockOffer[] { offer };
		}

		public synchronized long expires() {
			long last = 0;
			for(int i=0;i<offers.length;i++) {
				if(offers[i].offeredTime > last) last = offers[i].offeredTime;
			}
			return last + OFFER_EXPIRY_TIME;
		}

		public synchronized boolean isEmpty(long now) {
			for(int i=0;i<offers.length;i++) {
				if(!offers[i].isExpired(now)) return false;
			}
			return true;
		}

		public void deleteOffer(BlockOffer offer) {
			if(logMINOR) Logger.minor(this, "Deleting "+offer+" from "+this);
			synchronized(this) {
				int idx = -1;
				final int offerLength = offers.length;
				for(int i=0;i<offerLength;i++) {
					if(offers[i] == offer) idx = i;
				}
				if(idx < 0) return;
				BlockOffer[] newOffers = new BlockOffer[offerLength - 1];
				if(idx > 0)
					System.arraycopy(offers, 0, newOffers, 0, idx);
				if(idx < newOffers.length)
					System.arraycopy(offers, idx + 1, newOffers, idx, offers.length - idx - 1);
				offers = newOffers;
			}
			if(offers.length < 1) {
				synchronized(FailureTable.this) {
					blockOfferListByKey.removeKey(entry.key);
				}
				node.clientCore.dequeueOfferedKey(entry.key);
			}
		}

		public synchronized void addOffer(BlockOffer offer) {
			BlockOffer[] newOffers = new BlockOffer[offers.length+1];
			System.arraycopy(offers, 0, newOffers, 0, offers.length);
			newOffers[offers.length] = offer;
			offers = newOffers;
		}
		
		public String toString() {
			return super.toString()+"("+offers.length+")";
		}
	}
	
	static final class BlockOffer {
		final long offeredTime;
		/** Either offered by or offered to this node */
		final WeakReference nodeRef;
		/** Authenticator */
		final byte[] authenticator;
		/** Boot ID when the offer was made */
		final long bootID;
		
		BlockOffer(PeerNode pn, long now, byte[] authenticator, long bootID) {
			this.nodeRef = pn.myRef;
			this.offeredTime = now;
			this.authenticator = authenticator;
			this.bootID = bootID;
		}

		public PeerNode getPeerNode() {
			return (PeerNode) nodeRef.get();
		}

		public boolean isExpired(long now) {
			return now > (offeredTime + OFFER_EXPIRY_TIME);
		}

		public boolean isExpired() {
			return isExpired(System.currentTimeMillis());
		}
	}
	
	/**
	 * Called when a data block is found (after it has been stored; there is a good chance of its being available in the
	 * near future). If there are nodes waiting for it, we will offer it to them.
	 */
	public void onFound(KeyBlock block) {
		if(!(node.enableULPRDataPropagation || node.enablePerNodeFailureTables)) return;
		Key key = block.getKey();
		if(key == null) throw new NullPointerException();
		FailureTableEntry entry;
		synchronized(this) {
			entry = (FailureTableEntry) entriesByKey.get(key);
			if(entry == null) return; // Nobody cares
			entriesByKey.removeKey(key);
			blockOfferListByKey.removeKey(key);
		}
		if(!node.enableULPRDataPropagation) return;
		entry.offer();
	}
	
	/** Run onOffer() on a separate thread since it can block for disk I/O, and we don't want to cause 
	 * transfer timeouts etc because of slow disk. */
	private final SerialExecutor offerExecutor;
	
	/**
	 * Called when we get an offer for a key. If this is an SSK, we will only accept it if we have previously asked for it.
	 * If it is a CHK, we will accept it if we want it.
	 * @param key The key we are being offered.
	 * @param peer The node offering it.
	 * @param authenticator 
	 */
	void onOffer(final Key key, final PeerNode peer, final byte[] authenticator) {
		if(!node.enableULPRDataPropagation) return;
		if(logMINOR)
			Logger.minor(this, "Offered key "+key+" by peer "+peer);
		FailureTableEntry entry;
		synchronized(this) {
			entry = (FailureTableEntry) entriesByKey.get(key);
			if(entry == null) {
				if(logMINOR) Logger.minor(this, "We didn't ask for the key");
				return; // we haven't asked for it
			}
		}
		offerExecutor.execute(new Runnable() {
			public void run() {
				innerOnOffer(key, peer, authenticator);
			}
		}, "onOffer()");
	}

	/**
	 * This method runs on the SerialExecutor. Therefore, any blocking network I/O needs to be scheduled
	 * on a separate thread. However, blocking disk I/O *should happen on this thread*. We deliberately
	 * serialise it, as high latencies can otherwise result.
	 */
	protected void innerOnOffer(Key key, PeerNode peer, byte[] authenticator) {
		//NB: node.hasKey() executes a datastore fetch
		if(node.hasKey(key)) {
			Logger.minor(this, "Already have key");
			return;
		}
		
		// Re-check after potentially long disk I/O.
		FailureTableEntry entry;
		long now = System.currentTimeMillis();
		synchronized(this) {
			entry = (FailureTableEntry) entriesByKey.get(key);
			if(entry == null) {
				if(logMINOR) Logger.minor(this, "We didn't ask for the key");
				return; // we haven't asked for it
			}
		}

		/*
		 * Accept (subject to later checks) if we asked for it.
		 * Should we accept it if we were asked for it? This is "bidirectional propagation".
		 * It's good because it makes the whole structure much more reliable; it's bad because
		 * it's not entirely under our control - we didn't choose to route it to the node, the node
		 * routed it to us. Now it's found it before we did...
		 * 
		 * Attacks:
		 * - Frost spamming etc: Is it easier to offer data to our peers rather than inserting it? Will
		 * it result in it being propagated further? The peer node would then do the request, rather than
		 * this node doing an insert. Is that beneficial?
		 * 
		 * Not relevant with CHKs anyway.
		 * 
		 * On the plus side, propagation to nodes that have asked is worthwhile because reduced polling 
		 * cost enables more secure messaging systems e.g. outbox polling...
		 * - Social engineering: If a key is unpopular, you can put a different copy of it on different 
		 * nodes. You can then use this to trace the requestor - identify that he is or isn't on the target. 
		 * You can't do this with a regular insert because it will often go several nodes even at htl 0. 
		 * With subscriptions, you might be able to bypass this - but only if you know no other nodes in the
		 * neighbourhood are subscribed. Easier with SSKs; with CHKs you have only binary information of 
		 * whether the person got the key (with social engineering). Hard to exploit on darknet; if you're 
		 * that close to the suspect there are easier ways to get at them e.g. correlation attacks.
		 * 
		 * Conclusion: We should accept the request if:
		 * - We asked for it from that node. (Note that a node might both have asked us and been asked).
		 * - That node asked for it, and it's a CHK.
		 */
		
		boolean weAsked = entry.askedFromPeer(peer, now);
		boolean heAsked = entry.askedByPeer(peer, now);
		if(!(weAsked || ((key instanceof NodeCHK) && heAsked))) {
			if(logMINOR) Logger.minor(this, "Not propagating key: weAsked="+weAsked+" heAsked="+heAsked);
			if(entry.isEmpty(now)) {
				synchronized(this) {
					entriesByKey.removeKey(key);
				}
			}
			return;
		}
		if(entry.isEmpty(now)) entriesByKey.removeKey(key);
		
		// Valid offer.
		
		// Add to offers list
		
		synchronized(this) {
			
			if(logMINOR) Logger.minor(this, "Valid offer");
			BlockOfferList bl = (BlockOfferList) blockOfferListByKey.get(key);
			BlockOffer offer = new BlockOffer(peer, now, authenticator, peer.getBootID());
			if(bl == null) {
				bl = new BlockOfferList(entry, offer);
			} else {
				bl.addOffer(offer);
			}
			blockOfferListByKey.push(key, bl);
			trimOffersList(now);
		}
		
		// Now, does anyone want it?
		
		node.clientCore.maybeQueueOfferedKey(key, entry.othersWant(peer));
	}

	private synchronized void trimOffersList(long now) {
		while(true) {
			if(blockOfferListByKey.isEmpty()) return;
			BlockOfferList bl = (BlockOfferList) blockOfferListByKey.peekValue();
			if(bl.isEmpty(now) || bl.expires() < now || blockOfferListByKey.size() > MAX_OFFERS) {
				if(logMINOR) Logger.minor(this, "Removing block offer list "+bl+" list size now "+blockOfferListByKey.size());
				blockOfferListByKey.popKey();
			} else {
				return;
			}
		}
	}

	/**
	 * We offered a key, a node has responded to the offer. Note that this runs on the incoming
	 * packets thread so should allocate a new thread if it does anything heavy. Note also that
	 * it is responsible for unlocking the UID.
	 * @param key The key to send.
	 * @param isSSK Whether it is an SSK.
	 * @param uid The UID.
	 * @param source The node that asked for the key.
	 * @throws NotConnectedException If the sender ceases to be connected.
	 */
	public void sendOfferedKey(final Key key, final boolean isSSK, final boolean needPubKey, final long uid, final PeerNode source) throws NotConnectedException {
		this.offerExecutor.execute(new Runnable() {
			public void run() {
				try {
					innerSendOfferedKey(key, isSSK, needPubKey, uid, source);
				} catch (NotConnectedException e) {
					// Too bad.
				}
			}
		}, "sendOfferedKey");
	}

	/**
	 * This method runs on the SerialExecutor. Therefore, any blocking network I/O needs to be scheduled
	 * on a separate thread. However, blocking disk I/O *should happen on this thread*. We deliberately
	 * serialise it, as high latencies can otherwise result.
	 */
	protected void innerSendOfferedKey(Key key, final boolean isSSK, boolean needPubKey, final long uid, final PeerNode source) throws NotConnectedException {
		if(isSSK) {
			SSKBlock block = node.fetch((NodeSSK)key, false);
			if(block == null) {
				// Don't have the key
				source.sendAsync(DMT.createFNPGetOfferedKeyInvalid(uid, DMT.GET_OFFERED_KEY_REJECTED_NO_KEY), null, 0, senderCounter);
				return;
			}
			
			final Message data = DMT.createFNPSSKDataFoundData(uid, block.getRawData());
			Message headers = DMT.createFNPSSKDataFoundHeaders(uid, block.getRawHeaders());
			final int dataLength = block.getRawData().length;
			
			source.sendAsync(headers, null, 0, senderCounter);
			
			node.executor.execute(new PrioRunnable() {

				public int getPriority() {
					return NativeThread.HIGH_PRIORITY;
				}

				public void run() {
					try {
						source.sendThrottledMessage(data, dataLength, senderCounter, 60*1000, false);
					} catch (NotConnectedException e) {
						// :(
					} catch (WaitedTooLongException e) {
						// :<
						Logger.error(this, "Waited too long sending SSK data");
					} catch (SyncSendWaitedTooLongException e) {
						// Impossible
					} finally {
						node.unlockUID(uid, isSSK, false, false, true, false);
					}
				}
				
			}, "Send offered SSK");
			
			if(RequestHandler.SEND_OLD_FORMAT_SSK) {
				Message df = DMT.createFNPSSKDataFound(uid, block.getRawHeaders(), block.getRawData());
				source.sendAsync(df, null, 0, senderCounter);
			}
			if(needPubKey) {
				Message pk = DMT.createFNPSSKPubKey(uid, block.getPubKey());
				source.sendAsync(pk, null, 0, senderCounter);
			}
		} else {
			CHKBlock block = node.fetch((NodeCHK)key, false);
			if(block == null) {
				// Don't have the key
				source.sendAsync(DMT.createFNPGetOfferedKeyInvalid(uid, DMT.GET_OFFERED_KEY_REJECTED_NO_KEY), null, 0, senderCounter);
				return;
			}
			Message df = DMT.createFNPCHKDataFound(uid, block.getRawHeaders());
			source.sendAsync(df, null, 0, senderCounter);
        	PartiallyReceivedBlock prb =
        		new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE, block.getRawData());
        	final BlockTransmitter bt =
        		new BlockTransmitter(node.usm, source, uid, prb, senderCounter);
        	node.executor.execute(new PrioRunnable() {

				public int getPriority() {
					return NativeThread.HIGH_PRIORITY;
				}

				public void run() {
					try {
						bt.send(node.executor);
					} catch (Throwable t) {
						Logger.error(this, "Sending offered key failed: "+t, t);
					} finally {
						node.unlockUID(uid, isSSK, false, false, true, false);
					}
				}
        		
        	}, "CHK offer sender");
		}
	}

	public final OfferedKeysByteCounter senderCounter = new OfferedKeysByteCounter();
	
	class OfferedKeysByteCounter implements ByteCounter {

		public void receivedBytes(int x) {
			node.nodeStats.offeredKeysSenderReceivedBytes(x);
		}

		public void sentBytes(int x) {
			node.nodeStats.offeredKeysSenderSentBytes(x);
		}

		public void sentPayload(int x) {
			node.sentPayload(x);
			node.nodeStats.offeredKeysSenderSentBytes(-x);
		}
		
	}
	
	class OfferList {

		OfferList(BlockOfferList offerList) {
			this.offerList = offerList;
			recentOffers = new Vector();
			expiredOffers = new Vector();
			long now = System.currentTimeMillis();
			BlockOffer[] offers = offerList.offers;
			for(int i=0;i<offers.length;i++) {
				if(!offers[i].isExpired(now))
					recentOffers.add(offers[i]);
				else
					expiredOffers.add(offers[i]);
			}
			if(logMINOR)
				Logger.minor(this, "Offers: "+recentOffers.size()+" recent "+expiredOffers.size()+" expired");
		}
		
		private final BlockOfferList offerList;
		
		private final Vector recentOffers;
		private final Vector expiredOffers;
		
		/** The last offer we returned */
		private BlockOffer lastOffer;
		
		public BlockOffer getFirstOffer() {
			if(lastOffer != null) {
				throw new IllegalStateException("Last offer not dealt with");
			}
			if(!recentOffers.isEmpty()) {
				int x = node.random.nextInt(recentOffers.size());
				return lastOffer = (BlockOffer) recentOffers.remove(x);
			}
			if(!expiredOffers.isEmpty()) {
				int x = node.random.nextInt(expiredOffers.size());
				return lastOffer = (BlockOffer) expiredOffers.remove(x);
			}
			// No more offers.
			return null;
		}
		
		/**
		 * Delete the last offer - we have used it, successfully or not.
		 */
		public void deleteLastOffer() {
			offerList.deleteOffer(lastOffer);
			lastOffer = null;
		}

		/**
		 * Keep the last offer - we weren't able to use it e.g. because of RejectedOverload.
		 * Maybe it will be useful again in the future.
		 */
		public void keepLastOffer() {
			lastOffer = null;
		}
		
	}

	public OfferList getOffers(Key key) {
		if(!node.enableULPRDataPropagation) return null;
		BlockOfferList bl;
		synchronized(this) {
			bl = (BlockOfferList) blockOfferListByKey.get(key);
			if(bl == null) return null;
		}
		return new OfferList(bl);
	}

	/** Called when a node disconnects */
	public void onDisconnect(final PeerNode pn) {
		if(!(node.enableULPRDataPropagation || node.enablePerNodeFailureTables)) return;
		// FIXME do something (off thread if expensive)
	}

	public TimedOutNodesList getTimedOutNodesList(Key key) {
		if(!node.enablePerNodeFailureTables) return null;
		synchronized(this) {
			return (FailureTableEntry) entriesByKey.get(key);
		}
	}
	
	public class FailureTableCleaner implements Runnable {

		public void run() {
			try {
				realRun();
			} catch (Throwable t) {
				Logger.error(this, "FailureTableCleaner caught "+t, t);
			} finally {
				node.ps.queueTimedJob(this, CLEANUP_PERIOD);
			}
		}

		private void realRun() {
			logMINOR = Logger.shouldLog(Logger.MINOR, FailureTable.this);
			logDEBUG = Logger.shouldLog(Logger.DEBUG, FailureTable.this);
			if(logMINOR) Logger.minor(this, "Starting FailureTable cleanup");
			long startTime = System.currentTimeMillis();
			FailureTableEntry[] entries;
			synchronized(FailureTable.this) {
				entries = new FailureTableEntry[entriesByKey.size()];
				entriesByKey.valuesToArray(entries);
			}
			for(int i=0;i<entries.length;i++) {
				if(entries[i].cleanup()) {
					synchronized(FailureTable.this) {
						if(entries[i].isEmpty()) {
							entriesByKey.removeKey(entries[i].key);
						}
					}
				}
			}
			long endTime = System.currentTimeMillis();
			if(logMINOR) Logger.minor(this, "Finished FailureTable cleanup took "+(endTime-startTime)+"ms");
		}

	}

	public boolean peersWantKey(Key key) {
		FailureTableEntry entry;
		synchronized(this) {
			entry = (FailureTableEntry) entriesByKey.get(key);
			if(entry == null) return false; // Nobody cares
		}
		return entry.othersWant(null);
	}

	public void handleLowMemory() throws Exception {
		synchronized (this) {
			int size = entriesByKey.size();
			do {
				entriesByKey.popKey();
			} while (entriesByKey.size() >= size / 2);
		}
	}

	public void handleOutOfMemory() throws Exception {
		synchronized (this) {
			entriesByKey.clear();
		}
	}
}
