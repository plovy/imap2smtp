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

import java.util.Properties;

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
				System.out.println(message);
			}
		});
		return result;
	}

	@Bean
	public ImapIdleChannelAdapter imapAdapter() {
		Properties conf = new Properties();
		conf.setProperty("mail.imap.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
		conf.setProperty("mail.imap.socketFactory.fallback", "false");
		conf.setProperty("mail.store.protocol", "imaps");
		conf.setProperty("mail.debug", "false");
		ImapMailReceiver receiver = new ImapMailReceiver("imaps://logmaster.ascalo19@gmail.com:gA?p!2dE@imap.gmail.com:465/inbox");
		receiver.setJavaMailProperties(conf);
		ImapIdleChannelAdapter result = new ImapIdleChannelAdapter(receiver);
		result.setOutputChannel(messageChannel());
		return result;
	}
}
