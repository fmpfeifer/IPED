package dpf.mg.udi.gpinf.whatsappextractor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import dpf.sp.gpinf.indexer.util.DBList;
import dpf.sp.gpinf.indexer.util.ModifiedList;

/**
 *
 * @author Fabio Melo Pfeifer <pfeifer.fmp@dpf.gov.br>
 */
public class Chat {

    private long id;
    private final WAContact remote;
    private String subject;
    private Supplier<DBList<Message>> messagesSupplier;
    private List<Message> messages = null;
    private String title = null;
    private boolean groupChat = false;

    private String recoveredFrom = null;

    private List<WAContact> groupmembers = new ArrayList<>();

    public Chat(WAContact remote) {
        this.remote = remote;
    }

    /**
     * @return the id
     */
    public long getId() {
        return id;
    }

    /**
     * @param id
     *            the id to set
     */
    public void setId(long id) {
        this.id = id;
    }

    /**
     * @return the subject
     */
    public String getSubject() {
        return subject;
    }

    /**
     * @param subject
     *            the subject to set
     */
    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getPrintId() {
        return remote.getId();
    }

    /**
     * @return the messages
     */
    public List<Message> getMessages() {
        if (messages == null) {
            messages = new ModifiedList<>(messagesSupplier.get());
        }
        return messages;
    }

    /**
     * @param messages
     *            the messages to set
     */
    public void setMessagesSupplier(Supplier<DBList<Message>> messagesSupplier) {
        this.messagesSupplier = messagesSupplier;
    }

    public boolean isGroupChat() {
        return groupChat;
    }

    public void setGroupChat(boolean groupChat) {
        this.groupChat = groupChat;
    }

    public String getTitle() {
        if (title == null) {
            if (isGroupChat()) {
                if (getSubject() != null && getSubject().trim().length() != 0) {
                    title = "WhatsApp Group - " + getSubject(); //$NON-NLS-1$
                } else {
                    title = "WhatsApp Group - " + getPrintId(); //$NON-NLS-1$
                }
            } else {
                title = "WhatsApp Chat - "; //$NON-NLS-1$
                if (remote != null && !remote.getName().trim().equals(getPrintId()))
                    title += remote.getName() + " - "; //$NON-NLS-1$
                title += getPrintId();
            }
        }
        return title;
    }

    public WAContact getRemote() {
        return remote;
    }

    public String getRecoveredFrom() {
        return recoveredFrom;
    }

    public void setRecoveredFrom(String recoveredFrom) {
        this.recoveredFrom = recoveredFrom;
    }

    public List<WAContact> getGroupmembers() {
        return groupmembers;
    }

    public void setGroupmembers(List<WAContact> groupmembers) {
        this.groupmembers = groupmembers;
    }
}
