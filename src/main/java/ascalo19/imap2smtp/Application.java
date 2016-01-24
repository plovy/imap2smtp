package ascalo19.imap2smtp;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.mail.ImapIdleChannelAdapter;
import org.springframework.integration.mail.ImapMailReceiver;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.support.ExecutorSubscribableChannel;

import javax.mail.Address;
import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Properties;
import java.util.concurrent.Exchanger;

@SpringBootApplication
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@Bean
	public SubscribableChannel messageChannel() {
		SubscribableChannel result = new ExecutorSubscribableChannel();
		result.subscribe(new MessageHandler() {
			@Override
			public void handleMessage(Message<?> message) throws MessagingException {
				try {
					MimeMessage email = (MimeMessage) message.getPayload();
					System.out.println(email.getSubject());
					String[] r = email.getHeader("Received");
					for (int i = r.length - 1; i >= 0; i--) {
						if (StringUtils.contains(r[i], "for ")) {
							System.out.println(StringUtils.substringBetween(r[i], "for ", " "));
							break;
						}
					}
					Address to = new InternetAddress("<pascal.lovy@iteral.ch>");
					mailForwarder().forward(new Address[]{to}, email);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
		return result;
	}

	@Bean
	public ImapMailReceiver imapReceiver() {
		Properties conf = new Properties();
		conf.setProperty("mail.imap.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
		conf.setProperty("mail.imap.socketFactory.fallback", "false");
		conf.setProperty("mail.store.protocol", "imaps");
		conf.setProperty("mail.debug", "false");
		ImapMailReceiver result = new ImapMailReceiver("imaps://imap.gmail.com:993/inbox");
		result.setJavaMailProperties(conf);
		result.setJavaMailAuthenticator(new Authenticator() {
			@Override
			protected PasswordAuthentication getPasswordAuthentication() {
				return new PasswordAuthentication("logmaster.ascalo19@gmail.com", "PASSWORD");
			}
		});
		return result;
	}

	@Bean
	public ImapIdleChannelAdapter imapAdapter() {
		ImapIdleChannelAdapter result = new ImapIdleChannelAdapter(imapReceiver());
		result.setOutputChannel(messageChannel());
		return result;
	}

	@Bean
	public JavaMailForwarder mailForwarder() {
		JavaMailForwarder result = new JavaMailForwarder();
		result.setHost("localhost");
		result.setPort(2525);
		return result;
	}
}
