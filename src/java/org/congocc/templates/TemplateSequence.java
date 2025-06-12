package org.congocc.templates;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * An interface you can implement to indicate that an object can 
 * be treated as a list. Not only can you iterate over it, but you
 * can get get the elements using an integer index. 
 * In Congo Templates, the normal usage is just to use regular 
 * Java objects from the standard class library, i.e. java.util.List
 */
public interface TemplateSequence extends Iterable<Object> {

    /**
     * Retrieves the i-th template model in this sequence.
     * 
     * @return the item at the specified index, or <code>null</code> if
     * the index is out of bounds. Note that a <code>null</code> value is
     * interpreted by the template engine as "variable does not exist", and accessing
     * a missing variables is usually considered as an error in the Congo
     * Template Language, so the usage of a bad index will not remain hidden.
     */
    Object get(int index);

    /**
     * @return the number of items in the list.
     */
    int size();

    default Iterator<Object> iterator() {
        return new Iterator<Object>() {
            int index;
            public boolean hasNext() {
                return index < size();
            }
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                return get(index++);
            }
        };
    }
}
