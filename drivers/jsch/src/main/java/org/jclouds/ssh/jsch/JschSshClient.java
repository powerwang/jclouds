/**
 *
 * Copyright (C) 2011 Cloud Conscious, LLC. <info@cloudconscious.com>
 *
 * ====================================================================
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
 * limitations under the License.
 * ====================================================================
 */
package org.jclouds.ssh.jsch;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.instanceOf;
import static com.google.common.base.Predicates.or;
import static com.google.common.base.Throwables.getCausalChain;
import static com.google.common.collect.Iterables.any;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.util.Arrays;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.inject.Named;

import org.apache.commons.io.input.ProxyInputStream;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.jclouds.compute.domain.ExecResponse;
import org.jclouds.http.handlers.BackoffLimitedRetryHandler;
import org.jclouds.io.Payload;
import org.jclouds.io.Payloads;
import org.jclouds.logging.Logger;
import org.jclouds.net.IPSocket;
import org.jclouds.ssh.ChannelNotOpenException;
import org.jclouds.ssh.SshClient;
import org.jclouds.ssh.SshException;
import org.jclouds.util.Strings2;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.io.Closeables;
import com.google.inject.Inject;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

/**
 * This class needs refactoring. It is not thread safe.
 * 
 * @author Adrian Cole
 */
public class JschSshClient implements SshClient {

   private final class CloseFtpChannelOnCloseInputStream extends ProxyInputStream {

      private final ChannelSftp sftp;

      private CloseFtpChannelOnCloseInputStream(InputStream proxy, ChannelSftp sftp) {
         super(proxy);
         this.sftp = sftp;
      }

      @Override
      public void close() throws IOException {
         super.close();
         if (sftp != null)
            sftp.disconnect();
      }
   }

   private final String host;
   private final int port;
   private final String username;
   private final String password;

   @Inject(optional = true)
   @Named("jclouds.ssh.max_retries")
   @VisibleForTesting
   int sshRetries = 5;

   @Inject(optional = true)
   @Named("jclouds.ssh.retryable_messages")
   @VisibleForTesting
   String retryableMessages = "failed to send channel request,channel is not opened,invalid data,End of IO Stream Read,Connection reset,connection is closed by foreign host,socket is not established";

   @Inject(optional = true)
   @Named("jclouds.ssh.retry_predicate")
   private Predicate<Throwable> retryPredicate = or(instanceOf(ConnectException.class), instanceOf(IOException.class));

   @Resource
   @Named("jclouds.ssh")
   protected Logger logger = Logger.NULL;

   private Session session;
   private final byte[] privateKey;
   final byte[] emptyPassPhrase = new byte[0];
   private final int timeout;
   private final BackoffLimitedRetryHandler backoffLimitedRetryHandler;

   public JschSshClient(BackoffLimitedRetryHandler backoffLimitedRetryHandler, IPSocket socket, int timeout,
         String username, String password, byte[] privateKey) {
      this.host = checkNotNull(socket, "socket").getAddress();
      checkArgument(socket.getPort() > 0, "ssh port must be greater then zero" + socket.getPort());
      checkArgument(password != null || privateKey != null, "you must specify a password or a key");
      this.port = socket.getPort();
      this.username = checkNotNull(username, "username");
      this.backoffLimitedRetryHandler = checkNotNull(backoffLimitedRetryHandler, "backoffLimitedRetryHandler");
      this.timeout = timeout;
      this.password = password;
      this.privateKey = privateKey;
   }

   @Override
   public void put(String path, String contents) {
      put(path, Payloads.newStringPayload(checkNotNull(contents, "contents")));
   }

   private void checkConnected() {
      checkState(session != null && session.isConnected(), String.format("(%s) Session not connected!", toString()));
   }

   public static interface Connection<T> {
      void clear();

      T create() throws Exception;
   }

   Connection<Session> sessionConnection = new Connection<Session>() {

      @Override
      public void clear() {
         if (session != null && session.isConnected()) {
            session.disconnect();
            session = null;
         }
      }

      @Override
      public Session create() throws Exception {
         JSch jsch = new JSch();
         session = jsch.getSession(username, host, port);
         if (timeout != 0)
            session.setTimeout(timeout);
         if (password != null) {
            session.setPassword(password);
         } else {
            // jsch wipes out your private key
            jsch.addIdentity(username, Arrays.copyOf(privateKey, privateKey.length), null, emptyPassPhrase);
         }
         java.util.Properties config = new java.util.Properties();
         config.put("StrictHostKeyChecking", "no");
         session.setConfig(config);
         session.connect();
         return session;
      }

      @Override
      public String toString() {
         return String.format("Session(%s)", JschSshClient.this.toString());
      }
   };

   protected <T, C extends Connection<T>> T acquire(C connection) {
      connection.clear();
      Exception e = null;
      String errorMessage = String.format("(%s) error acquiring %s", toString(), connection);
      for (int i = 0; i < sshRetries; i++) {
         try {
            logger.debug(">> (%s) acquiring %s", toString(), connection);
            T returnVal = connection.create();
            logger.debug("<< (%s) acquired %s", toString(), returnVal);
            return returnVal;
         } catch (Exception from) {
            e = from;
            connection.clear();

            if (i == sshRetries)
               throw propagate(from, errorMessage);

            if (shouldRetry(from)) {
               logger.warn("<< " + errorMessage + ": " + from.getMessage());
               backoffForAttempt(i + 1, errorMessage + ": " + from.getMessage());
               continue;
            }
            throw propagate(from, errorMessage);
         }
      }
      if (e != null)
         throw propagate(e, errorMessage);
      return null;
   }

   @PostConstruct
   public void connect() {
      acquire(sessionConnection);
   }

   Connection<ChannelSftp> sftpConnection = new Connection<ChannelSftp>() {

      private ChannelSftp sftp;

      @Override
      public void clear() {
         if (sftp != null)
            sftp.disconnect();
      }

      @Override
      public ChannelSftp create() throws JSchException {
         checkConnected();
         String channel = "sftp";
         sftp = (ChannelSftp) session.openChannel(channel);
         try {
            sftp.connect();
         } catch (JSchException e) {
            throwChannelNotOpenExceptionOrPropagate(channel, e);
         }
         return sftp;
      }

      @Override
      public String toString() {
         return "ChannelSftp(" + JschSshClient.this.toString() + ")";
      }
   };

   public void throwChannelNotOpenExceptionOrPropagate(String channel, JSchException e) throws JSchException {
      if (e.getMessage().indexOf("channel is not opened") != -1)
         throw new ChannelNotOpenException(String.format("(%s) channel %s is not open", toString(), channel), e);
   }

   class GetConnection implements Connection<Payload> {
      private final String path;
      private ChannelSftp sftp;

      GetConnection(String path) {
         this.path = checkNotNull(path, "path");
      }

      @Override
      public void clear() {
         if (sftp != null)
            sftp.disconnect();
      }

      @Override
      public Payload create() throws Exception {
         sftp = acquire(sftpConnection);
         return Payloads.newInputStreamPayload(new CloseFtpChannelOnCloseInputStream(sftp.get(path), sftp));
      }

      @Override
      public String toString() {
         return "Payload(" + JschSshClient.this.toString() + ")[" + path + "]";
      }
   };

   public Payload get(String path) {
      return acquire(new GetConnection(path));
   }

   class PutConnection implements Connection<Void> {
      private final String path;
      private final Payload contents;
      private ChannelSftp sftp;

      PutConnection(String path, Payload contents) {
         this.path = checkNotNull(path, "path");
         this.contents = checkNotNull(contents, "contents");
      }

      @Override
      public void clear() {
         if (sftp != null)
            sftp.disconnect();
      }

      @Override
      public Void create() throws Exception {
         sftp = acquire(sftpConnection);
         InputStream is = checkNotNull(contents.getInput(), "inputstream for path %s", path);
         try {
            sftp.put(is, path);
         } finally {
            Closeables.closeQuietly(contents);
            clear();
         }
         return null;
      }

      @Override
      public String toString() {
         return "Put(" + JschSshClient.this.toString() + ")[" + path + "]";
      }
   };

   @Override
   public void put(String path, Payload contents) {
      acquire(new PutConnection(path, contents));
   }

   @VisibleForTesting
   boolean shouldRetry(Exception from) {
      return any(getCausalChain(from), retryPredicate)
            || any(Splitter.on(",").split(retryableMessages), causalChainHasMessageContaining(from));
   }

   @VisibleForTesting
   Predicate<String> causalChainHasMessageContaining(final Exception from) {
      return new Predicate<String>() {

         @Override
         public boolean apply(final String input) {
            return any(getCausalChain(from), new Predicate<Throwable>() {

               @Override
               public boolean apply(Throwable arg0) {
                  return arg0.getMessage() != null && arg0.getMessage().indexOf(input) != -1;
               }

            });
         }

      };
   }

   private void backoffForAttempt(int retryAttempt, String message) {
      backoffLimitedRetryHandler.imposeBackoffExponentialDelay(200L, 2, retryAttempt, sshRetries, message);
   }

   private SshException propagate(Exception e, String message) {
      message += ": " + e.getMessage();
      logger.error(e, "<< " + message);
      throw e instanceof SshException ? SshException.class.cast(e) : new SshException(
            "(" + toString() + ") " + message, e);
   }

   @Override
   public String toString() {
      return String.format("%s@%s:%d", username, host, port);
   }

   @PreDestroy
   public void disconnect() {
      sessionConnection.clear();
   }

   protected Connection<ChannelExec> execConnection(final String command) {
      checkNotNull(command, "command");
      return new Connection<ChannelExec>() {

         private ChannelExec executor = null;

         @Override
         public void clear() {
            if (executor != null)
               executor.disconnect();
         }

         @Override
         public ChannelExec create() throws Exception {
            checkConnected();
            String channel = "exec";
            executor = (ChannelExec) session.openChannel(channel);
            executor.setPty(true);
            executor.setCommand(command);
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            executor.setErrStream(error);
            try {
               executor.connect();
            } catch (JSchException e) {
               throwChannelNotOpenExceptionOrPropagate("exec", e);
            }
            return executor;
         }

         @Override
         public String toString() {
            return "ChannelExec(" + JschSshClient.this.toString() + ")";
         }
      };

   }

   class ExecConnection implements Connection<ExecResponse> {
      private final String command;
      private ChannelExec executor;

      ExecConnection(String command) {
         this.command = checkNotNull(command, "command");
      }

      @Override
      public void clear() {
         if (executor != null)
            executor.disconnect();
      }

      @Override
      public ExecResponse create() throws Exception {
         executor = acquire(execConnection(command));
         try {
            String outputString = Strings2.toStringAndClose(executor.getInputStream());
            String errorString = Strings2.toStringAndClose(executor.getErrStream());
            int errorStatus = executor.getExitStatus();
            int i = 0;
            String message = String.format("bad status -1 %s", toString());
            while ((errorStatus = executor.getExitStatus()) == -1 && i < JschSshClient.this.sshRetries) {
               logger.warn("<< " + message);
               backoffForAttempt(++i, message);
            }
            if (errorStatus == -1)
               throw new SshException(message);
            return new ExecResponse(outputString, errorString, errorStatus);
         } finally {
            if (executor != null)
               executor.disconnect();
         }
      }

      @Override
      public String toString() {
         return "ExecResponse(" + JschSshClient.this.toString() + ")[" + command + "]";
      }
   }

   public ExecResponse exec(String command) {
      return acquire(new ExecConnection(command));
   }

   @Override
   public String getHostAddress() {
      return this.host;
   }

   @Override
   public String getUsername() {
      return this.username;
   }

}
