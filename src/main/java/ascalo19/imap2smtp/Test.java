package ascalo19.imap2smtp;

import com.sun.mail.imap.IMAPFolder;

import javax.mail.Folder;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.event.*;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Test {

    public static void main(String[] args) {
        try {
            System.out.println("Hello");

            Properties conf = new Properties();
            conf.setProperty("mail.smtp.host", "smtp.gmail.com");
            conf.setProperty("mail.smtp.socketFactory.port", "465");
            conf.setProperty("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            conf.setProperty("mail.smtp.auth", "true");
            conf.setProperty("mail.smtp.port", "465");

            Session session = Session.getDefaultInstance(conf, null);

            Store store = session.getStore("imaps");
            store.connect("smtp.gmail.com", "logmaster.ascalo19@gmail.com", "gA?p!2dE");

            IMAPFolder f = (IMAPFolder)store.getFolder("INBOX");
            f.open(Folder.READ_WRITE);
            f.addMessageChangedListener(new MessageChangedListener() {
                @Override
                public void messageChanged(MessageChangedEvent messageChangedEvent) {
                    try {
                        System.out.println(messageChangedEvent.getMessage().getSubject());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });

            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        while (true) {
                            f.idle();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });

            t.start();
            t.join();

            System.out.println(f.getMessageCount());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
