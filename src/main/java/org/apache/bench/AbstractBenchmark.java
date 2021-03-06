package org.apache.bench;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public abstract class AbstractBenchmark implements Benchmark {
  private final CmdArgs config;

  public AbstractBenchmark(final CmdArgs config) {
    this.config = Preconditions.checkNotNull(config);
  }

  protected abstract void setup(CuratorFramework client) throws Exception;

  protected abstract void teardown(CuratorFramework client) throws Exception;

  @Override
  public Result run() {

    List<FailureResult> failures = Lists.newArrayList();
    List<SuccessResult> successes = Lists.newArrayList();

    final CuratorFramework setupClient = createClient(config);

    try {
      setupClient.start();
      setup(setupClient);
    } catch (Exception e) {
      failures.add(new FailureResult("Failure during setup.", Lists.newArrayList(e)));
    } finally {
      if (setupClient != null) {
        try {
          setupClient.close();
        } catch (Exception e) {
          failures.add(new FailureResult("Failure while closing curator framework.", Lists.newArrayList(e)));
        }
      }
    }

    if (!failures.isEmpty()) {
      return FailureResult.aggregate(failures);
    }

    final ListeningExecutorService executorService = MoreExecutors.listeningDecorator(
      Executors.newFixedThreadPool(config.getNumClients()));

    List<Task> tasks = Lists.newArrayList();
    List<ListenableFuture<Result>> futures = Lists.newArrayList();

    for (int taskCount = 0; taskCount < config.getNumClients(); taskCount++) {
      final Task task = createTask(config);
      final ListenableFuture<Result> future = executorService.submit(task);

      tasks.add(task);
      futures.add(future);
    }

    long endTime = System.currentTimeMillis() + config.getDurationInMillis();
    long currentTime;

    while ((currentTime = System.currentTimeMillis()) < endTime) {
      long sleepTime = endTime - currentTime;
      try {
        Thread.sleep(sleepTime);
      } catch (InterruptedException e) {
        failures.add(new FailureResult("Terminating the benchmark early."));
        break;
      }
    }

    tasks.forEach(task -> task.terminate());

    if (failures.isEmpty()) {
      ListenableFuture<List<Result>> uberFuture = Futures.allAsList(futures);
      List<Result> results = null;

      try {
        results = uberFuture.get(1, TimeUnit.MINUTES);
      } catch (InterruptedException | ExecutionException | TimeoutException e) {
        failures.add(new FailureResult("Failed while waiting to get results of all tasks",
          Lists.newArrayList(e)));
      }

      if (failures.isEmpty()) {
        results.forEach(result -> {
          switch (result.getType()) {
            case SUCCESS:
              successes.add((SuccessResult) result);
              break;
            case FAILURE:
              failures.add((FailureResult) result);
              break;
          }
        });
      }
    }

    executorService.shutdown();

    try {
      executorService.awaitTermination(1, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      FailureResult failureResult = new FailureResult(
        "Timed out while shutting down executor service",
        Lists.newArrayList(e));

      failures.add(failureResult);
      executorService.shutdownNow();
    }

    final CuratorFramework teardownClient = createClient(config);

    try {
      teardownClient.start();
      setup(teardownClient);
    } catch (Exception e) {
      failures.add(new FailureResult("Failure during teardown.", Lists.newArrayList(e)));
    } finally {
      if (teardownClient != null) {
        try {
          teardownClient.close();
        } catch (Exception e) {
          failures.add(new FailureResult("Failure during teardown.", Lists.newArrayList(e)));
        }
      }
    }

    if (!failures.isEmpty()) {
      return FailureResult.aggregate(failures);
    }

    return aggregateMetrics(successes);
  }

  protected abstract SuccessResult aggregateMetrics(List<SuccessResult> metrics);

  protected abstract Task createTask(final CmdArgs cmdArgs);

  public static CuratorFramework createClient(final CmdArgs cmdArgs) {
    final RetryPolicy policy = new RetryNTimes(3, 10000);
    final CuratorFramework client = CuratorFrameworkFactory.newClient(cmdArgs.getConnectionString(), policy);
    return client;
  }

  public static abstract class AbstractTask implements Task {
    private final CmdArgs cmdArgs;
    private volatile boolean terminated = false;

    public AbstractTask(final CmdArgs cmdArgs) {
      this.cmdArgs = Preconditions.checkNotNull(cmdArgs);
    }

    @Override
    public Result call() throws Exception {
      CuratorFramework client = null;
      List<Exception> exceptionList = new ArrayList<>();
      Result result = null;

      try {
        client = createClient(cmdArgs);
        client.start();
        result = runTask(client);
      } catch (Exception ex) {
        exceptionList.add(ex);
      } finally {
        if (client != null) {
          try {
            client.close();
          } catch (Exception exClose) {
            exceptionList.add(exClose);
          }
        }
      }

      if (!exceptionList.isEmpty() || result == null) {
        return new FailureResult(exceptionList);
      } else {
        return result;
      }
    }

    protected boolean isTerminated() {
      return terminated;
    }

    @Override
    public void terminate() {
      this.terminated = true;
    }

    @Override
    public void throttleIfRequired(long currentTput) {
      while (cmdArgs.getRequiredThrougput() < currentTput) {
        try {
          Thread.sleep(1);
        } catch (InterruptedException ex) {
          // ignore
        }
      }
    }
  }
}
