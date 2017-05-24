package ascalo19.imap2smtp;

import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMailMessage;
import org.springframework.mail.javamail.MimeMessagePreparator;

import javax.mail.Address;
import javax.mail.AuthenticationFailedException;
import javax.mail.MessagingException;
import javax.mail.Transport;
import javax.mail.internet.MimeMessage;
import java.util.*;

public class JavaMailForwarder extends JavaMailSenderImpl {

	private static final String HEADER_MESSAGE_ID = "Message-ID";

	public void forward(Address[] forwardTo, SimpleMailMessage simpleMessage) throws MailException {
		forward(forwardTo, new SimpleMailMessage[]{simpleMessage});
	}

	public void forward(Address[] forwardTo, SimpleMailMessage... simpleMessages) throws MailException {
		List<MimeMessage> mimeMessages = new ArrayList<MimeMessage>(simpleMessages.length);
		for (SimpleMailMessage simpleMessage : simpleMessages) {
			MimeMailMessage message = new MimeMailMessage(createMimeMessage());
			simpleMessage.copyTo(message);
			mimeMessages.add(message.getMimeMessage());
		}
		doForward(forwardTo, mimeMessages.toArray(new MimeMessage[mimeMessages.size()]), simpleMessages);
	}

	public void forward(Address[] forwardTo, MimeMessage mimeMessage) throws MailException {
		forward(forwardTo, new MimeMessage[]{mimeMessage});
	}

	public void forward(Address[] forwardTo, MimeMessage... mimeMessages) throws MailException {
		doForward(forwardTo, mimeMessages, null);
	}

	public void forward(Address[] forwardTo, MimeMessagePreparator mimeMessagePreparator) throws MailException {
		forward(forwardTo, new MimeMessagePreparator[]{mimeMessagePreparator});
	}

	public void forward(Address[] forwardTo, MimeMessagePreparator... mimeMessagePreparators) throws MailException {
		try {
			List<MimeMessage> mimeMessages = new ArrayList<MimeMessage>(mimeMessagePreparators.length);
			for (MimeMessagePreparator preparator : mimeMessagePreparators) {
				MimeMessage mimeMessage = createMimeMessage();
				preparator.prepare(mimeMessage);
				mimeMessages.add(mimeMessage);
			}
			forward(forwardTo, mimeMessages.toArray(new MimeMessage[mimeMessages.size()]));
		} catch (MailException ex) {
			throw ex;
		} catch (MessagingException ex) {
			throw new MailParseException(ex);
		} catch (Exception ex) {
			throw new MailPreparationException(ex);
		}
	}

	protected void doForward(Address[] forwardTo, MimeMessage[] mimeMessages, Object[] originalMessages) throws MailException {
		Map<Object, Exception> failedMessages = new LinkedHashMap<Object, Exception>();
		Transport transport = null;

		try {
			for (int i = 0; i < mimeMessages.length; i++) {

				// Check transport connection first...
				if (transport == null || !transport.isConnected()) {
					if (transport != null) {
						try {
							transport.close();
						} catch (Exception ex) {
							// Ignore - we're reconnecting anyway
						}
						transport = null;
					}
					try {
						transport = connectTransport();
					} catch (AuthenticationFailedException ex) {
						throw new MailAuthenticationException(ex);
					} catch (Exception ex) {
						// Effectively, all remaining messages failed...
						for (int j = i; j < mimeMessages.length; j++) {
							Object original = (originalMessages != null ? originalMessages[j] : mimeMessages[j]);
							failedMessages.put(original, ex);
						}
						throw new MailSendException("Mail server connection failed", ex, failedMessages);
					}
				}

				// Send message via current transport...
				MimeMessage mimeMessage = mimeMessages[i];
				try {
					if (mimeMessage.getSentDate() == null) {
						mimeMessage.setSentDate(new Date());
					}
					String messageId = mimeMessage.getMessageID();
					mimeMessage.saveChanges();
					if (messageId != null) {
						// Preserve explicitly specified message id...
						mimeMessage.setHeader(HEADER_MESSAGE_ID, messageId);
					}
					transport.sendMessage(mimeMessage, forwardTo);
				} catch (Exception ex) {
					Object original = (originalMessages != null ? originalMessages[i] : mimeMessage);
					failedMessages.put(original, ex);
					//ex.printStackTrace();
				}
			}
		} finally {
			try {
				if (transport != null) {
					transport.close();
				}
			} catch (Exception ex) {
				if (!failedMessages.isEmpty()) {
					throw new MailSendException("Failed to close server connection after message failures", ex,
							failedMessages);
				} else {
					throw new MailSendException("Failed to close server connection after message sending", ex);
				}
			}
		}

		if (!failedMessages.isEmpty()) {
			throw new MailSendException(failedMessages);
		}
	}
}
