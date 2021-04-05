package dpf.sp.gpinf.indexer.util;

import java.util.AbstractList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * This class is a List decorator that takes an unmodifiable as a backing storage. When this List is modified (have items
 * added, removed or modified), instead of modifying the backing list, it will record those modifications internally.
 * This list will work as if it has been modified, but the backing list won't be touched.
 * 
 * This class is supposed to be used when the backing list is big and should not be modified, and making a copy of
 * that list is expensive. So using this class only the modifications will be stored, saving memory.
 * 
 * @author Fábio Melo Pfeifer <fmpfeifer@gmail.com>
 *
 * @param <E>
 */
public class ModifiedList<E> extends AbstractList<E> {

    /**
     * Original list. This list will not be written to.
     */
    private List<E> backingList;
    
    public List<E> getBackingList() {
        return backingList;
    }

    /**
     * List of removed items from backingList. Indexes in the domain of backing list.
     */
    private Set<Integer> itensRemoved = new TreeSet<>();
    
    /**
     * List of inserted items to backingList. Indexes in the domain of modified list.
     */
    private Map<Integer, E> itensInserted = new TreeMap<>();
    
    public ModifiedList(List<E> backingList) {
        this.backingList = Collections.unmodifiableList(backingList);
    }
    
    @Override
    public int size() {
        return backingList.size() - itensRemoved.size() + itensInserted.size();
    }

    @Override
    public boolean add(E e) {
        itensInserted.put(size(), e);
        return true;
    }
    
    @Override
    public boolean remove(Object o) {
        int idx = indexOf(o);
        if (idx < 0) {
            return false;
        }
        remove(idx);
        return true;
    }

    @Override
    public E get(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException();
        }
        if (itensInserted.containsKey(index)) {
            return itensInserted.get(index);
        }
        return backingList.get(getIndexInBackingListDomain(index));
    }

    @Override
    public E set(int index, E element) {
        E removed = remove(index);
        add(index, element);
        return removed;
    }

    @Override
    public void add(int index, E element) {
        Map<Integer, E> newItensInserted = new TreeMap<>();
        for (Map.Entry<Integer, E> entry: itensInserted.entrySet()) {
            if (index <= entry.getKey()) {
                newItensInserted.put(entry.getKey() + 1, entry.getValue());
            } else if (index > entry.getKey()) {
                newItensInserted.put(entry.getKey(), entry.getValue());
            }
        }
        itensInserted = newItensInserted;
        itensInserted.put(index, element);
    }

    @Override
    public E remove(int index) {
        E removed = get(index);
        if (!itensInserted.containsKey(index)) {
            itensRemoved.add(getIndexInBackingListDomain(index));
        }
        Map<Integer, E> newItensInserted = new TreeMap<>();
        for (Map.Entry<Integer, E> entry: itensInserted.entrySet()) {
            if (entry.getKey() < index) {
                newItensInserted.put(entry.getKey(), entry.getValue());
            } else if (entry.getKey() > index) {
                newItensInserted.put(entry.getKey() - 1, entry.getValue());
            }
        }
        itensInserted = newItensInserted;
        return removed;
    }
    
    /**
     * Translate the index to the backing list domain
     * @param index the index to be translated
     * @return the corresponding index in the backing list
     */
    private int getIndexInBackingListDomain(int index) {
        int resultingIndex = index;
        for (int i: itensInserted.keySet()) {
            if ( i <= index) {
                resultingIndex -= 1;
            } else {
                break;
            }
        }
        for (int i: itensRemoved ) {
            if ( i <= resultingIndex) {
                resultingIndex += 1;
            } else {
                break;
            }
        }
        
        return resultingIndex;
    }
}
