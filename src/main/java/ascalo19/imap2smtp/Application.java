package ascalo19.imap2smtp;

import com.sun.mail.imap.IMAPFolder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.annotation.Transformer;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mail.ImapIdleChannelAdapter;
import org.springframework.integration.mail.ImapMailReceiver;
import org.springframework.integration.transformer.HeaderEnricher;
import org.springframework.integration.transformer.support.HeaderValueMessageProcessor;
import org.springframework.integration.transformer.support.StaticHeaderValueMessageProcessor;
import org.springframework.mail.MailSendException;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.support.ExecutorSubscribableChannel;

import javax.mail.Address;
import javax.mail.Authenticator;
import javax.mail.Folder;
import javax.mail.PasswordAuthentication;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.*;

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
	@Value("${imap.trash.folder}")
	private String imapTrashFolder;
	@Value("${imap.username}")
	private String imapUsername;
	@Value("${imap.password}")
	private String imapPassword;
	@Value("${smtp.protocol}")
	private String smtpProtocol;
	@Value("${smtp.host}")
	private String smtpHost;
	@Value("${smtp.port}")
	private Integer smtpPort;
	@Value("${smtp.username}")
	private String smtpUsername;
	@Value("${smtp.password}")
	private String smtpPassword;
	@Value("${additional.smtp.protocol:}")
	private String additionalSmtpProtocol;
	@Value("${additional.smtp.host:}")
	private String additionalSmtpHost;
	@Value("${additional.smtp.port:}")
	private Integer additionalSmtpPort;
	@Value("${additional.smtp.username:}")
	private String additionalSmtpUsername;
	@Value("${additional.smtp.password:}")
	private String additionalSmtpPassword;
	@Value("${relay.domains}")
	private String relayDomains;
	@Value("${relay.default.address}")
	private String relayDefaultAddress;

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@Bean
	public SubscribableChannel messageOutput() {
		String[] domains = StringUtils.split(relayDomains, ',');
		SubscribableChannel result = new ExecutorSubscribableChannel();
		result.subscribe(message -> {
			MimeMessage email = (MimeMessage) message.getPayload();
			try {
				if (message.getHeaders().containsKey("SPAM")) {
					email.setSubject("[SPAM] " + email.getSubject());
				}
				List<Address> recipients = new ArrayList<Address>();
				if (email.getAllRecipients() != null) {
					recipients.addAll(Arrays.asList(email.getAllRecipients()));
				}
				if (email.getHeader("Received") != null) {
					for (String header : email.getHeader("Received")) {
						if (StringUtils.contains(header, "for ")) {
							String recipient = StringUtils.substringBetween(header, "for ", ";");
							try {
								for (Address address : parseAddressList(recipient)) {
									recipients.add(address);
								}
							} catch (Exception e) {
								log.warn("Invalid address in Received header : " + recipient);
							}
						}
					}
				}

				Map<String, InternetAddress> uniqueRecipients = new HashMap<>();
				for (Address address : recipients) {
					InternetAddress internetAddress = parseAddress(address.toString());
					if (StringUtils.containsAny(internetAddress.getAddress(), domains)) {
						uniqueRecipients.put(internetAddress.getAddress(), parseAddress(internetAddress.getAddress()));
					}
				}

				if (uniqueRecipients.isEmpty()) {
					if (StringUtils.isNotBlank(relayDefaultAddress)) {
						uniqueRecipients.put(relayDefaultAddress, parseAddress(relayDefaultAddress));
					} else {
						throw new Exception("Message \"" + email.getSubject() + "\" doesn't target domains " + Arrays.toString(domains) + " in recipients " + recipients.toString());
					}
				}

				try {
					log.info("Delivering message \"" + email.getSubject() + "\" from " + Arrays.toString(email.getFrom()) + " to " + uniqueRecipients.values().toString());
					smtpForwarder().forward(uniqueRecipients.values().toArray(new Address[uniqueRecipients.size()]), email);
				} catch (MailSendException e) {
					uniqueRecipients.clear();
					uniqueRecipients.put(relayDefaultAddress, parseAddress(relayDefaultAddress));

					log.info("... delivery failed, about to deliver to default address");
					log.info("Delivering message \"" + email.getSubject() + "\" from " + Arrays.toString(email.getFrom()) + " to " + uniqueRecipients.values().toString());
					smtpForwarder().forward(uniqueRecipients.values().toArray(new Address[uniqueRecipients.size()]), email);
				}

				if (StringUtils.isNoneBlank(additionalSmtpHost)) {
					try {
						try {
							log.info("Additionally delivering message \"" + email.getSubject() + "\" from " + Arrays.toString(email.getFrom()) + " to " + uniqueRecipients.values().toString());
							additionalSmtpForwarder().forward(uniqueRecipients.values().toArray(new Address[uniqueRecipients.size()]), email);
						} catch (MailSendException e) {
							uniqueRecipients.clear();
							uniqueRecipients.put(relayDefaultAddress, parseAddress(relayDefaultAddress));

							log.info("... delivery failed, about to deliver to default address");
							log.info("Additionally delivering message \"" + email.getSubject() + "\" from " + Arrays.toString(email.getFrom()) + " to " + uniqueRecipients.values().toString());
							additionalSmtpForwarder().forward(uniqueRecipients.values().toArray(new Address[uniqueRecipients.size()]), email);
						}
					} catch (Exception e) {
						// Ignore (avoid reject)
					}
				}

				deleteMessage(email);

			} catch (Exception e) {
				sendAlert(email, e);
				rejectMessage(email);
			}
		});
		return result;
	}

	private InternetAddress[] parseAddressList(String address) throws AddressException {
		return InternetAddress.parseHeader(address, false);
	}

	private InternetAddress parseAddress(String address) throws AddressException {
		return parseAddressList(address)[0];
	}

	private void sendAlert(MimeMessage email, Exception e) {
		try {
			log.error("Error while delivering message " + email.getSubject(), e);
			// TODO Send alert
		} catch (Exception ex) {
			log.error("Unexpected error while sending alert for message " + email, ex);
		}
	}

	private void deleteMessage(MimeMessage email) {
		if (StringUtils.isNotBlank(imapTrashFolder)) {
			try {
				move(email, imapTrashFolder);
			} catch (Exception e) {
				log.error("Unexpected error while deleting message " + email, e);
			}
		}
	}

	private void rejectMessage(MimeMessage email) {
		if (StringUtils.isNotBlank(imapRejectFolder)) {
			try {
				move(email, imapRejectFolder);
			} catch (Exception e) {
				log.error("Unexpected error while rejecting message " + email, e);
			}
		}
	}

	private void move(MimeMessage email, String targetFolder) throws Exception {
		Folder source = email.getFolder();
		Folder target = source.getStore().getFolder(targetFolder);
		source.open(Folder.READ_WRITE);
		target.open(Folder.READ_WRITE);
		((IMAPFolder) source).moveMessages(new MimeMessage[]{(MimeMessage) FieldUtils.readField(email, "source", true)}, target);
		source.close(true);
		target.close(false);
	}

	@Bean
	public ImapMailReceiver imapInboxReceiver() {
		ImapMailReceiver result = new ImapMailReceiver(imapInboxUrl);
		result.setJavaMailProperties(javaMailProperties());
		result.setJavaMailAuthenticator(javaMailAuthenticator());
		return result;
	}

	@Bean
	public ImapMailReceiver imapRetryReceiver() {
		ImapMailReceiver result = new ImapMailReceiver(imapRetryUrl);
//		result.setSearchTermStrategy(new AllMessagesSearchTermStrategy());
		result.setJavaMailProperties(javaMailProperties());
		result.setJavaMailAuthenticator(javaMailAuthenticator());
		return result;
	}

	@Bean
	public ImapMailReceiver imapSpamReceiver() {
		ImapMailReceiver result = new ImapMailReceiver(imapSpamUrl);
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
		result.setProtocol(smtpProtocol);
		result.setHost(smtpHost);
		result.setPort(smtpPort);
		result.setUsername(smtpUsername);
		result.setPassword(smtpPassword);
		return result;
	}

	@Bean
	@ConditionalOnProperty(name = "additional.smtp.host")
	public JavaMailForwarder additionalSmtpForwarder() {
		JavaMailForwarder result = new JavaMailForwarder();
		result.setProtocol(additionalSmtpProtocol);
		result.setHost(additionalSmtpHost);
		result.setPort(additionalSmtpPort);
		result.setUsername(additionalSmtpUsername);
		result.setPassword(additionalSmtpPassword);
		return result;
	}

	@Bean
	public Properties javaMailProperties() {
		Properties result = new Properties();
		result.setProperty("mail.imap.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
		result.setProperty("mail.imap.socketFactory.fallback", "false");
		result.setProperty("mail.store.protocol", "imaps");
		result.setProperty("mail.mime.address.strict", "false");
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
