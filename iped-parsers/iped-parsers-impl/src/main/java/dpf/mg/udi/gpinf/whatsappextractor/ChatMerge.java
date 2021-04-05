package dpf.mg.udi.gpinf.whatsappextractor;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import dpf.sp.gpinf.indexer.util.ModifiedList;

public class ChatMerge {

    List<Chat> main;
    String dbname;

    Comparator<Message> cmpMessage = new Comparator<Message>() {

        @Override
        public int compare(Message u1, Message u2) {
            if (u1.getTimeStamp() != null && u2.getTimeStamp() != null) {
                int comp = u1.getTimeStamp().compareTo(u2.getTimeStamp());
                if (comp != 0) {
                    return comp;
                } else {
                    return compareId(u1, u2);
                }
            } else {
                return compareId(u1, u2);
            }
        }

        private int compareId(Message u1, Message u2) {
            if (u1.getId() == u2.getId()) {
                return 0;
            } else if (u1.getId() < u2.getId())
                return -1;
            else
                return 1;
        }
    };

    public ChatMerge(List<Chat> main, String dbname) {
        this.main = main;
        this.dbname = dbname;
    }

    public boolean isBackup(List<Chat> backup) {
        int indexmain = 0;
        int totchats = 0;
        for (Chat c : backup) {
            indexmain = findChat(main, c);
            if (indexmain >= 0) {// verify if the chats are compatible
                if (!c.getRemote().getId().equals(main.get(indexmain).getRemote().getId())) {
                    // if there is a chat incompatible the msgstore is not a backup
                    return false;
                } else if (hasCompatibleMessage(c.getMessages(), main.get(indexmain).getMessages())) {
                    totchats++;
                }
            }

        }
        return totchats > 0;
    }

    private boolean hasCompatibleMessage(List<Message> backup, List<Message> main) {
        quickSort(main, cmpMessage);
        int maxMsgsToCheck = 10;
        for (int i = 0; i < backup.size(); i += Math.max(1, backup.size() / maxMsgsToCheck)) {
            int idx = Collections.binarySearch(main, backup.get(i), cmpMessage);
            if (idx >= 0) {
                return true;
            }
        }
        return false;
    }

    public int mergeChatList(List<Chat> backup) {
        int tot_rec = 0;

        int indexmain = 0;

        for (Chat c : backup) {
            indexmain = findChat(main, c);
            if (indexmain == -1) {// chat was removed
                main.add(c);
                c.setRecoveredFrom(dbname);
                tot_rec += c.getMessages().size();
            } else {
                tot_rec += mergeMessageList(main.get(indexmain).getMessages(), c.getMessages());
            }

        }
        return tot_rec;

    }

    public int mergeMessageList(List<Message> main, List<Message> backup) {
        int tot_rec = 0;

        quickSort(main, cmpMessage);
        int indexmain = 0;
        for (Message m : backup) {

            indexmain = Collections.binarySearch(main, m, cmpMessage);
            if (indexmain < 0) {// message was removed
                main.add(-indexmain - 1, m);
                tot_rec++;
                m.setRecoveredFrom(dbname);
            }

        }
        return tot_rec;

    }

    private int findChat(List<Chat> l, Chat key) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).getId() == key.getId()) {
                return i;
            }
        }
        return -1;
    }

    private static <T> void quickSort(List<T> list, Comparator<T> comparator) {
        quickSort(list, 0, list.size() - 1, comparator);
    }

    private static <T> void quickSort(List<T> list, int low, int high, Comparator<T> comparator) {
        int i = low;
        int j = high;
        T pivot = list.get(low + (high - low) / 2);

        while (i <= j) {
            while (comparator.compare(list.get(i), pivot) < 0) {
                i++;
            }

            while (comparator.compare(list.get(j), pivot) > 0) {
                j--;
            }

            if (i <= j) {
                T temp = list.get(i);
                list.set(i, list.get(j));
                list.set(j, temp);
                i++;
                j--;
            }
        }

        if (low < j) {
            quickSort(list, low, j, comparator);
        }
        if (i < high) {
            quickSort(list, i, high, comparator);
        }
    }
}
