package ascalo19.imap2smtp;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.annotation.Transformer;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mail.ImapIdleChannelAdapter;
import org.springframework.integration.mail.ImapMailReceiver;
import org.springframework.integration.transformer.HeaderEnricher;
import org.springframework.integration.transformer.support.HeaderValueMessageProcessor;
import org.springframework.integration.transformer.support.StaticHeaderValueMessageProcessor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.support.ExecutorSubscribableChannel;

import javax.mail.Address;
import javax.mail.Authenticator;
import javax.mail.Folder;
import javax.mail.PasswordAuthentication;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

/**
 * Installation instructions
 * https://docs.spring.io/spring-boot/docs/current/reference/html/deployment-install.html
 */
@SpringBootApplication
public class Application {

	private static final Log log = LogFactory.getLog(Application.class);

	@Value("${imap.inbox.url}")
	private String imapInboxUrl;
	@Value("${imap.spam.url}")
	private String imapSpamUrl;
	@Value("${imap.retry.url}")
	private String imapRetryUrl;
	@Value("${imap.reject.folder}")
	private String imapRejectFolder;
	@Value("${imap.username}")
	private String imapUsername;
	@Value("${imap.password}")
	private String imapPassword;
	@Value("${smtp.host}")
	private String smtpHost;
	@Value("${smtp.port}")
	private Integer smtpPort;


	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@Bean
	public SubscribableChannel messageOutput() {
		SubscribableChannel result = new ExecutorSubscribableChannel();
		result.subscribe(new MessageHandler() {
			@Override
			public void handleMessage(Message<?> message) throws MessagingException {
				MimeMessage email = (MimeMessage) message.getPayload();
				try {
					if (message.getHeaders().containsKey("SPAM")) {
						email.setSubject("[SPAM] " + email.getSubject());
					}
					Address[] recipients = email.getAllRecipients();
					String[] receivedHeaders = email.getHeader("Received");
					for (int i = receivedHeaders.length - 1; i >= 0; i--) {
						if (StringUtils.contains(receivedHeaders[i], "for ")) {
							String recipient = StringUtils.substringBetween(receivedHeaders[i], "for ", " ");
							recipients = new Address[]{new InternetAddress(StringUtils.remove(recipient, ';'))};
							break;
						}
					}
					log.info("Delivering message \"" + email.getSubject() + "\" to " + Arrays.toString(recipients));
					smtpForwarder().forward(recipients, email);
				} catch (Exception e) {
					sendAlert(email, e);
					rejectMessage(email);
				}
			}
		});
		return result;
	}

	private void rejectMessage(MimeMessage email) {
		try {
			Folder folder = email.getFolder();
			Folder rejectFolder = folder.getStore().getFolder(imapRejectFolder);
			rejectFolder.open(Folder.READ_WRITE);
			rejectFolder.appendMessages(new MimeMessage[]{email});
			rejectFolder.close(false);
		} catch (Exception e) {
			log.error("Unexpected error while rejecting message " + email, e);
		}
	}

	private void sendAlert(MimeMessage email, Exception e) {
		try {
			log.error("Error while delivering message " + email.getSubject(), e);
			// TODO
		} catch (Exception ex) {
			log.error("Unexpected error while sending alert for message " + email, ex);
		}
	}

	@Bean
	public ImapMailReceiver imapInboxReceiver() {
		ImapMailReceiver result = new ImapMailReceiver(imapInboxUrl);
		result.setShouldMarkMessagesAsRead(false);
		result.setShouldDeleteMessages(true);
		result.setJavaMailProperties(javaMailProperties());
		result.setJavaMailAuthenticator(javaMailAuthenticator());
		return result;
	}

	@Bean
	public ImapMailReceiver imapRetryReceiver() {
		ImapMailReceiver result = new ImapMailReceiver(imapRetryUrl);
		result.setSearchTermStrategy(new AllMessagesSearchTermStrategy());
		result.setShouldMarkMessagesAsRead(false);
		result.setShouldDeleteMessages(true);
		result.setJavaMailProperties(javaMailProperties());
		result.setJavaMailAuthenticator(javaMailAuthenticator());
		return result;
	}

	@Bean
	public ImapMailReceiver imapSpamReceiver() {
		ImapMailReceiver result = new ImapMailReceiver(imapSpamUrl);
		result.setShouldMarkMessagesAsRead(false);
		result.setShouldDeleteMessages(true);
		result.setJavaMailProperties(javaMailProperties());
		result.setJavaMailAuthenticator(javaMailAuthenticator());
		return result;
	}

	@Bean
	public ImapIdleChannelAdapter messageInboxInput() {
		ImapIdleChannelAdapter result = new ImapIdleChannelAdapter(imapInboxReceiver());
		result.setOutputChannel(messageOutput());
		return result;
	}

	@Bean
	public ImapIdleChannelAdapter messageRetryInput() {
		ImapIdleChannelAdapter result = new ImapIdleChannelAdapter(imapRetryReceiver());
		result.setOutputChannel(messageOutput());
		return result;
	}

	@Bean
	public ImapIdleChannelAdapter messageSpamInput() {
		ImapIdleChannelAdapter result = new ImapIdleChannelAdapter(imapSpamReceiver());
		result.setOutputChannel(messageSpamTransfer());
		return result;
	}

	@Bean
	public DirectChannel messageSpamTransfer() {
		return new DirectChannel();
	}

	@Bean
	@Transformer(inputChannel = "messageSpamTransfer", outputChannel = "messageOutput")
	public HeaderEnricher enrichSpamHeader() {
		Map<String, ? extends HeaderValueMessageProcessor<?>> headersToAdd = Collections.singletonMap("SPAM", new StaticHeaderValueMessageProcessor<>(Boolean.TRUE));
		HeaderEnricher enricher = new HeaderEnricher(headersToAdd);
		return enricher;
	}

	@Bean
	public JavaMailForwarder smtpForwarder() {
		JavaMailForwarder result = new JavaMailForwarder();
		result.setHost(smtpHost);
		result.setPort(smtpPort);
		return result;
	}

	@Bean
	public Properties javaMailProperties() {
		Properties result = new Properties();
		result.setProperty("mail.imap.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
		result.setProperty("mail.imap.socketFactory.fallback", "false");
		result.setProperty("mail.store.protocol", "imaps");
		result.setProperty("mail.debug", "false");
		return result;
	}

	@Bean
	public Authenticator javaMailAuthenticator() {
		Authenticator result = new Authenticator() {
			@Override
			protected PasswordAuthentication getPasswordAuthentication() {
				return new PasswordAuthentication(imapUsername, imapPassword);
			}
		};
		return result;
	}
}
