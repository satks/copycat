/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package io.atomix.copycat.test;

import io.atomix.catalyst.transport.Address;
import io.atomix.catalyst.transport.LocalServerRegistry;
import io.atomix.catalyst.transport.LocalTransport;
import io.atomix.copycat.client.Command;
import io.atomix.copycat.client.CopycatClient;
import io.atomix.copycat.client.Query;
import io.atomix.copycat.server.Commit;
import io.atomix.copycat.server.CopycatServer;
import io.atomix.copycat.server.StateMachine;
import io.atomix.copycat.server.StateMachineExecutor;
import io.atomix.copycat.server.storage.Storage;
import net.jodah.concurrentunit.ConcurrentTestCase;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Copycat cluster test.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
@Test
public class ClusterTest extends ConcurrentTestCase {
  private static final File directory = new File("test-logs");
  protected LocalServerRegistry registry;
  protected int port;
  protected List<Address> members;

  /**
   * Tests submitting a command.
   */
  public void testSubmitCommandWithNoneConsistency() throws Throwable {
    testSubmitCommand(Command.ConsistencyLevel.NONE);
  }

  /**
   * Tests submitting a command.
   */
  public void testSubmitCommandWithSequentialConsistency() throws Throwable {
    testSubmitCommand(Command.ConsistencyLevel.SEQUENTIAL);
  }

  /**
   * Tests submitting a command.
   */
  public void testSubmitCommandWithLinearizableConsistency() throws Throwable {
    testSubmitCommand(Command.ConsistencyLevel.LINEARIZABLE);
  }

  /**
   * Tests submitting a command with a configured consistency level.
   */
  private void testSubmitCommand(Command.ConsistencyLevel consistency) throws Throwable {
    createServers(5);

    CopycatClient client = createClient();
    client.submit(new TestCommand("Hello world!", consistency)).thenAccept(result -> {
      threadAssertEquals(result, "Hello world!");
      resume();
    });

    await();
  }

  /**
   * Tests submitting a query.
   */
  public void testSubmitQueryWithCausalConsistency() throws Throwable {
    testSubmitQuery(Query.ConsistencyLevel.CAUSAL);
  }

  /**
   * Tests submitting a query.
   */
  public void testSubmitQueryWithSequentialConsistency() throws Throwable {
    testSubmitQuery(Query.ConsistencyLevel.SEQUENTIAL);
  }

  /**
   * Tests submitting a query.
   */
  public void testSubmitQueryWithBoundedLinearizableConsistency() throws Throwable {
    testSubmitQuery(Query.ConsistencyLevel.BOUNDED_LINEARIZABLE);
  }

  /**
   * Tests submitting a query.
   */
  public void testSubmitQueryWithLinearizableConsistency() throws Throwable {
    testSubmitQuery(Query.ConsistencyLevel.LINEARIZABLE);
  }

  /**
   * Tests submitting a query with a configured consistency level.
   */
  private void testSubmitQuery(Query.ConsistencyLevel consistency) throws Throwable {
    createServers(5);

    CopycatClient client = createClient();
    client.submit(new TestQuery("Hello world!", consistency)).thenAccept(result -> {
      threadAssertEquals(result, "Hello world!");
      resume();
    });

    await();
  }

  /**
   * Returns the next server address.
   *
   * @return The next server address.
   */
  private Address nextAddress() {
    Address address = new Address("localhost", port++);
    members.add(address);
    return address;
  }

  /**
   * Creates a set of Copycat servers.
   */
  private List<CopycatServer> createServers(int nodes) throws Throwable {
    List<CopycatServer> servers = new ArrayList<>();

    List<Address> members = new ArrayList<>();
    for (int i = 1; i <= nodes; i++) {
      members.add(nextAddress());
    }

    for (int i = 0; i < nodes; i++) {
      CopycatServer server = createServer(members.get(i));
      server.open().thenRun(this::resume);
      servers.add(server);
    }

    await(0, nodes);

    return servers;
  }

  /**
   * Creates a Copycat server.
   */
  private CopycatServer createServer(Address address) {
    return CopycatServer.builder(address, members)
      .withTransport(new LocalTransport(registry))
      .withStorage(Storage.builder()
        .withDirectory(new File(directory, address.toString()))
        .build())
      .withStateMachine(new TestStateMachine())
      .build();
  }

  /**
   * Creates a Copycat client.
   */
  private CopycatClient createClient() throws Throwable {
    CopycatClient client = CopycatClient.builder(members).withTransport(new LocalTransport(registry)).build();
    client.open().thenRun(this::resume);
    await();
    return client;
  }

  @BeforeMethod
  @AfterMethod
  public void clearTests() throws IOException {
    deleteDirectory(directory);
    port = 5000;
    registry = new LocalServerRegistry();
    members = new ArrayList<>();
  }

  /**
   * Deletes a directory recursively.
   */
  private void deleteDirectory(File directory) throws IOException {
    if (directory.exists()) {
      File[] files = directory.listFiles();
      if (files != null) {
        for (File file : files) {
          if (file.isDirectory()) {
            deleteDirectory(file);
          } else {
            Files.delete(file.toPath());
          }
        }
      }
      Files.delete(directory.toPath());
    }
  }

  /**
   * Test state machine.
   */
  public static class TestStateMachine extends StateMachine {
    @Override
    protected void configure(StateMachineExecutor executor) {
      executor.register(TestCommand.class, this::command);
      executor.register(TestQuery.class, this::query);
    }

    private String command(Commit<TestCommand> commit) {
      return commit.operation().value();
    }

    private String query(Commit<TestQuery> commit) {
      return commit.operation().value();
    }
  }

  /**
   * Test command.
   */
  public static class TestCommand implements Command<String> {
    private String value;
    private ConsistencyLevel consistency;

    public TestCommand(String value, ConsistencyLevel consistency) {
      this.value = value;
      this.consistency = consistency;
    }

    @Override
    public ConsistencyLevel consistency() {
      return consistency;
    }

    public String value() {
      return value;
    }

    @Override
    public int groupCode() {
      return value.hashCode();
    }

    @Override
    public boolean groupEquals(Command command) {
      return command instanceof TestCommand && ((TestCommand) command).value.equals(value);
    }
  }

  /**
   * Test query.
   */
  public static class TestQuery implements Query<String> {
    private String value;
    private ConsistencyLevel consistency;

    public TestQuery(String value, ConsistencyLevel consistency) {
      this.value = value;
      this.consistency = consistency;
    }

    @Override
    public ConsistencyLevel consistency() {
      return consistency;
    }

    public String value() {
      return value;
    }
  }

}