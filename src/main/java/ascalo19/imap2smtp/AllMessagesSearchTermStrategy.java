package ascalo19.imap2smtp;

import org.springframework.integration.mail.SearchTermStrategy;

import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.search.SearchTerm;

public class AllMessagesSearchTermStrategy implements SearchTermStrategy {

	@Override
	public SearchTerm generateSearchTerm(Flags supportedFlags, Folder folder) {
		return null;
	}
}
