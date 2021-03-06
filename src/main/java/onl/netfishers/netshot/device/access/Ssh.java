/**
 * Copyright 2013-2016 Sylvain Cadilhac (NetFishers)
 * 
 * This file is part of Netshot.
 * 
 * Netshot is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Netshot is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Netshot.  If not, see <http://www.gnu.org/licenses/>.
 */
package onl.netfishers.netshot.device.access;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.security.Security;

import onl.netfishers.netshot.Netshot;
import onl.netfishers.netshot.device.NetworkAddress;
import onl.netfishers.netshot.work.TaskLogger;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Identity;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An SSH CLI access.
 */
public class Ssh extends Cli {
	
	private static Logger logger = LoggerFactory.getLogger(Ssh.class);

	/** Default value for the SSH connection timeout */
	static private int DEFAULT_CONNECTION_TIMEOUT = 5000;

	static {
		Security.insertProviderAt(new BouncyCastleProvider(), 1);

		int configuredConnectionTimeout = Netshot.getConfig("netshot.cli.ssh.connectiontimeout", DEFAULT_CONNECTION_TIMEOUT);
		if (configuredConnectionTimeout < 1) {
			logger.error("Invalid value {} for {}", configuredConnectionTimeout, "netshot.cli.ssh.connectiontimeout");			
		}
		else {
			DEFAULT_CONNECTION_TIMEOUT = configuredConnectionTimeout;
		}
		logger.info("The default connection timeout value for SSH sessions is {}s", DEFAULT_CONNECTION_TIMEOUT);
	}
	
	/** The port. */
	private int port = 22;
	
	/** The jsch. */
	private JSch jsch;
	
	/** The session. */
	private Session session;
	
	/** The channel. */
	private Channel channel;
	
	/** The username. */
	private String username;
	
	/** The password or passphrase. */
	private String password;
	
	/** The public key. */
	private String publicKey = null;
	
	/** The private key. */
	private String privateKey = null;
	
	/**
	 * Instantiates a new SSH connection (password authentication).
	 *
	 * @param host the host
	 * @param port the port
	 * @param username the username
	 * @param password the password
	 * @param taskLogger the current task logger
	 */
	public Ssh(NetworkAddress host, int port, String username, String password, TaskLogger taskLogger) {
		super(host, taskLogger);
		if (port != 0) this.port = port;
		this.username = username;
		this.password = password;
		this.privateKey = null;
		this.connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
	}
	
	/**
	 * Instantiates a new SSH connection (private key authentication).
	 * 
	 * @param host the host
	 * @param port the port
	 * @param username the SSH username
	 * @param privateKey the RSA/DSA private key
	 * @param passphrase the passphrase which protects the private key
	 * @param taskLogger the current task logger
	 */
	public Ssh(NetworkAddress host, int port, String username, String publicKey, String privateKey,
			String passphrase, TaskLogger taskLogger) {
		super(host, taskLogger);
		if (port != 0) this.port = port;
		this.username = username;
		this.publicKey = publicKey;
		this.privateKey = privateKey;
		this.password = passphrase;
		this.connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
	}
	
	/**
	 * Instantiates a new SSH connection (private key authentication).
	 * 
	 * @param host the host
	 * @param username the SSH username
	 * @param privateKey the RSA/DSA private key
	 * @param passphrase the passphrase which protects the private key
	 * @param taskLogger the current task logger
	 */
	public Ssh(NetworkAddress host, String username, String publicKey, String privateKey,
			String passphrase, TaskLogger taskLogger) {
		super(host, taskLogger);
		this.username = username;
		this.publicKey = publicKey;
		this.privateKey = privateKey;
		this.password = passphrase;
		this.connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
	}
	
	
	/**
	 * Instantiates a new ssh.
	 *
	 * @param host the host
	 * @param username the username
	 * @param password the password
	 * @param taskLogger the current task logger
	 */
	public Ssh(NetworkAddress host, String username, String password, TaskLogger taskLogger) {
		super(host, taskLogger);
		this.username = username;
		this.password = password;
		this.privateKey = null;
		this.connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
	}

	/* (non-Javadoc)
	 * @see onl.netfishers.netshot.device.access.Cli#connect()
	 */
	@Override
	public void connect() throws IOException {
		jsch = new JSch();
		try {
			if (privateKey != null && publicKey != null) {
				KeyPair kpair = KeyPair.load(jsch, privateKey.getBytes(), publicKey.getBytes());
				jsch.addIdentity(new Identity() {
					@Override
					public boolean setPassphrase(byte[] passphrase) throws JSchException {
						return kpair.decrypt(passphrase);
					}
					@Override
					public boolean isEncrypted() {
						return kpair.isEncrypted();
					}
					@Override
					public byte[] getSignature(byte[] data) {
					    return kpair.getSignature(data);
					}
					@Override
					public byte[] getPublicKeyBlob() {
					    return kpair.getPublicKeyBlob();
					}
					@Override
					public String getName() {
						return "Key";
					}
					@Override
					public String getAlgName() {
						switch (kpair.getKeyType()) {
						case KeyPair.RSA:
							return "ssh-rsa";
						case KeyPair.DSA:
							return "ssh-dss";
						case KeyPair.ERROR:
							return "ERROR";
						default:
							return "UNKNOWN";
						}
					}
					@Override
					public boolean decrypt() {
						throw new RuntimeException("Not implemented");
					}
					@Override
					public void clear() {
						kpair.dispose();
					}
				}, password == null ? null : password.getBytes());
			}
			session = jsch.getSession(username, host.getIp(), port);
			if (privateKey == null || publicKey == null) {
				session.setPassword(password);
			}
			// Disable Strict Key checking
			session.setConfig("StrictHostKeyChecking", "no");
			session.setTimeout(this.receiveTimeout);
			session.connect(this.connectionTimeout);
			channel = session.openChannel("shell");
			this.inStream = channel.getInputStream();
			this.outStream = new PrintStream(channel.getOutputStream());
			channel.connect(this.connectionTimeout);
		}
		catch (JSchException e) {
			throw new IOException(e.getMessage(), e);
		}
		
	}

	/* (non-Javadoc)
	 * @see onl.netfishers.netshot.device.access.Cli#disconnect()
	 */
	@Override
	public void disconnect() {
		try {
			if (channel != null) {
				channel.disconnect();
			}
			if (session != null) {
				session.disconnect();
			}
		}
		catch (Exception e) {
			logger.warn("Error on SSH disconnect.", e);
		}
	}

	private int scpCheckAck(InputStream in) throws IOException {
		int b = in.read();
		// 0 = success, 1 = error, 2 = fatal error, -1 = ...
		if (b == 1 || b == 2) {
			StringBuffer sb = new StringBuffer("SCP error: ");
			int c;
			do {
				c = in.read();
				sb.append((char) c);
			}
			while (c != '\n');
			throw new IOException(sb.toString());
		}
		return b;
	}

	/**
	 * Download a file using SCP.
	 * @param remoteFileName The file to download (name with full path) from the device
	 * @param localFileName  The local file name (name with full path) where to write
	 */
	public void scpDownload(String remoteFileName, String localFileName) throws IOException {
		if (localFileName == null) {
			throw new FileNotFoundException("Invalid destination file name for SCP copy operation. "
				+ "Have you defined 'netshot.snapshots.binary.path'?");
		}
		if (remoteFileName == null) {
			throw new FileNotFoundException("Invalid source file name for SCP copy operation");
		}
		if (!session.isConnected()) {
			throw new IOException("The SSH session is not connected, can't start the SCP transfer");
		}
		this.taskLogger.info(String.format("Downloading '%s' to '%s' using SCP.",
				remoteFileName, localFileName.toString()));

		Channel channel = null;
		FileOutputStream fileStream = null;
		try {
			String command = String.format("scp -f '%s'", remoteFileName.replace("'", "'\"'\"'"));
			channel = session.openChannel("exec");
			((ChannelExec) channel).setCommand(command);
			OutputStream out = channel.getOutputStream();
			InputStream in = channel.getInputStream();
			channel.connect(this.connectionTimeout);

			byte[] buf = new byte[4096];

			// Send null
			buf[0] = 0;
			out.write(buf, 0, 1);
			out.flush();

			if (this.scpCheckAck(in) != 'C') {
				throw new IOException("SCP error, no file to download");
			}

			// Read (and ignore) file permissions
			in.read(buf, 0, 5);

			long fileSize = 0L;
			while (true) {
				if (in.read(buf, 0, 1) < 0 || buf[0] == ' ') {
					break;
				}
				fileSize = fileSize * 10L + (long)(buf[0] - '0');
			}
			taskLogger.debug(String.format("File size: %d bytes", fileSize));

			String retFileName = null;
			int i = 0;
			while (true) {
				if (in.read(buf, i, 1) < 0) {
					throw new IOException("Can't receive the file name");
				}
				if (buf[i] == (byte)0x0a) {
					retFileName = new String(buf, 0, i);
					break;
				}
				i += 1;
			}

			taskLogger.debug(String.format("Name: '%s'", retFileName));

			// Send null
			buf[0] = 0;
			out.write(buf, 0, 1);
			out.flush();

			fileStream = new FileOutputStream(localFileName);
			while (true) {
				int len = in.read(buf, 0, Math.min(buf.length, (int)fileSize));
				if (len < 0) {
					throw new IOException("SCP read error");
				}
				fileStream.write(buf, 0, len);
				fileSize -= len;
				if (fileSize == 0L) {
					break;
				}
			}
			fileStream.close();
			fileStream = null;

		}
		catch (JSchException error) {
			throw new IOException("SCP exception", error);
		}
		finally {
			try {
				channel.disconnect();
			}
			catch (Exception e1) {
				// This is life
			}
			try {
				fileStream.close();
			}
			catch (Exception e1) {
				// This is life
			}
		}

	}

	/**
	 * Download a file using SFTP.
	 * @param remoteFileName The file to download (name with full path) from the device
	 * @param localFileName  The local file name (name with full path) where to write
	 */
	public void sftpDownload(String remoteFileName, String localFileName) throws IOException {
		if (localFileName == null) {
			throw new FileNotFoundException("Invalid destination file name for SFTP copy operation. "
				+ "Have you defined 'netshot.snapshots.binary.path'?");
		}
		if (remoteFileName == null) {
			throw new FileNotFoundException("Invalid source file name for SFTP copy operation");
		}
		if (!session.isConnected()) {
			throw new IOException("The SSH session is not connected, can't start the SFTP transfer");
		}
		this.taskLogger.info(String.format("Downloading '%s' to '%s' using SFTP.",
				remoteFileName, localFileName.toString()));

		Channel channel = null;
		try {
			channel = session.openChannel("sftp");
			channel.connect(this.connectionTimeout);
			ChannelSftp sftpChannel = (ChannelSftp) channel;
			sftpChannel.get(remoteFileName, localFileName);
		}
		catch (JSchException error) {
			throw new IOException("SSH error: " + error.getMessage(), error);
		}
		catch (SftpException error) {
			throw new IOException("SFTP error: " + error.getCause().getMessage(), error);
		}
		finally {
			try {
				channel.disconnect();
			}
			catch (Exception e1) {
				// This is life
			}
		}

	}

}
