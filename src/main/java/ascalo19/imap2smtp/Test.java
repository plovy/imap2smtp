package ascalo19.imap2smtp;


import com.sun.mail.imap.IdleManager;

import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.event.MessageCountAdapter;
import javax.mail.event.MessageCountEvent;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Test {

	public static void main(String[] args) {
		try {

			// https://javamail.java.net/nonav/docs/api/index.html
			// https://github.com/spring-projects/spring-integration-samples/tree/master/basic/mail
			// http://docs.spring.io/spring-integration/docs/current/api/org/springframework/integration/mail/ImapMailReceiver.html

			System.out.println("Hello");

			Properties conf = new Properties();
			conf.setProperty("mail.smtp.host", "smtp.gmail.com");
			conf.setProperty("mail.smtp.socketFactory.port", "465");
			conf.setProperty("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
			conf.setProperty("mail.smtp.auth", "true");
			conf.setProperty("mail.smtp.port", "465");

			Session session = Session.getDefaultInstance(conf, null);

			ExecutorService es = Executors.newCachedThreadPool();
			IdleManager idleManager = new IdleManager(session, es);

			Store store = session.getStore("imaps");
			store.connect("smtp.gmail.com", "logmaster.ascalo19@gmail.com", "gA?p!2dE");

			Folder folder = store.getFolder("INBOX");
			folder.open(Folder.READ_WRITE);
			folder.addMessageCountListener(new MessageCountAdapter() {
				public void messagesAdded(MessageCountEvent ev) {
					Folder folder = (Folder) ev.getSource();
					Message[] msgs = ev.getMessages();
					System.out.println("Folder: " + folder +
						" got " + msgs.length + " new messages");
					try {
						// process new messages
						idleManager.watch(folder); // keep watching for new messages
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			});
			idleManager.watch(folder);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
