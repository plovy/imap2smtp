package ascalo19.imap2smtp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.mail.ImapIdleChannelAdapter;
import org.springframework.integration.mail.ImapMailReceiver;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.support.ExecutorSubscribableChannel;

import javax.mail.Authenticator;
import javax.mail.PasswordAuthentication;
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
				return new PasswordAuthentication("logmaster.ascalo19@gmail.com", "gA?p!2dE");
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
}
