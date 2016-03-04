/*
*
*@Author: Manoj Kumar Vohra
*@Created: 16-02-2016
*
*/
package com.bigdata.curator;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.atomic.AtomicValue;
import org.apache.curator.framework.recipes.atomic.DistributedAtomicLong;
import org.apache.curator.framework.recipes.atomic.PromotedToLock;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.retry.RetryNTimes;
import org.apache.curator.retry.RetryUntilElapsed;
import org.apache.log4j.Logger;

public class HLSequenceIncrementer {

	private CuratorFramework curator;
	private String counterPath;
	private DistributedAtomicLong jvmCounter;
	private transient Logger logger = Logger.getLogger(this.getClass());

	public HLSequenceIncrementer(String zkAddress, String counterPath, Long startHIValue) throws Exception {
		this.curator = CuratorFrameworkFactory.newClient(zkAddress, new RetryNTimes(5, 1000));
		curator.start();
		this.counterPath = counterPath;
		createCounter(startHIValue);
	}

	private void createCounter(Long startHIValue) throws Exception {
		int maxRetryTimeInMillis = 1000;
		int retryIntervalInMillis = 100;
		RetryPolicy rp = new RetryUntilElapsed(maxRetryTimeInMillis,retryIntervalInMillis);
		RetryPolicy lockPromotionRetryPolicy = new ExponentialBackoffRetry(3, 3);
		PromotedToLock promotedToLock = PromotedToLock.builder().lockPath("/lock").retryPolicy(lockPromotionRetryPolicy)
				.build();

		startHIValue = startHIValue != null ? startHIValue : -1;

		if (notInitialised()) {
			this.jvmCounter = new DistributedAtomicLong(this.curator, this.counterPath, rp, promotedToLock);
			/* if the node /counterPath already exists, the below intialisation wouldn't make any difference.
			For seed values its recommended to check zookeeper for non existence of the /counterPath*/
			this.jvmCounter.initialize(startHIValue);
		} else {
			/*This will sync the atomic long with current existing value at /counterPath*/
			this.jvmCounter = new DistributedAtomicLong(this.curator, this.counterPath, rp, promotedToLock);
		}
	}

	public Long increment() throws Exception {
		Long currentCounter = null;
		try {

			if (this.jvmCounter.get().succeeded()) {

				AtomicValue<Long> incrementState = this.jvmCounter.increment();

				if (incrementState.succeeded()) {
					currentCounter = incrementState.postValue();
				}
			}

		} catch (Exception ex) {
			logger.error("********* INCREMENT COUNTER ERROR: " + ex.getMessage());
			throw new RuntimeException("Error incrementing sequence high counter: " + ex.getMessage());
		}
		return currentCounter;
	}

	public boolean notInitialised() throws Exception {
		try {
			return curator.checkExists().forPath(this.counterPath) == null;

		} catch (Exception ex) {
			logger.error("********* Error in fetching counter details: " + ex.getMessage());
			throw new RuntimeException("Error in fetching counter details: " + ex.getMessage());
		}
	}

	public void removeSequenceCounters() {

		try {
			curator.delete().forPath(this.counterPath);
		} catch (Exception e) {
			throw new RuntimeException("Error deleting sequence high counter: " + e.getMessage());
		}

	}
}
