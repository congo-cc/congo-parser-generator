package org.congocc.output;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class Sequencer {
    private HashSet<String> nodes = new HashSet<>();
    private HashMap<String, HashSet<String>> preds = new HashMap<>();
    private HashMap<String, HashSet<String>> succs = new HashMap<>();

    private static HashSet<String> EMPTY_SET = new HashSet<>();

    public void addNode(String node) {
        nodes.add(node);
    }

    public void removeNode(String node) {
        removeNode(node, false);
    }

    public void removeNode(String node, boolean edges) {
        nodes.remove(node);
        if (edges) {
            for (String p : preds.getOrDefault(node, EMPTY_SET)) {
                remove(p, node);
            }
            for (String s : succs.getOrDefault(node, EMPTY_SET)) {
                remove(node, s);
            }
            // remove empties
            for (Map.Entry<String, HashSet<String>> entry : preds.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    preds.remove(entry.getKey());
                }
            }
            for (Map.Entry<String, HashSet<String>> entry : succs.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    succs.remove(entry.getKey());
                }
            }
        }
    }

    public void add(String pred, String succ) {
        HashSet<String> set;

        if (pred.equals(succ)) {
            throw new IllegalArgumentException(String.format("predecessor & successor can't be the same: %s", pred));
        }
        if ((set = preds.get(succ)) == null) {
            set = new HashSet<>();
            preds.put(succ, set);
        }
        set.add(pred);
        if ((set = succs.get(pred)) == null) {
            set = new HashSet<>();
            succs.put(pred, set);
        }
        set.add(succ);
    }

    public void remove(String pred, String succ) {
        if (pred.equals(succ)) {
            throw new IllegalArgumentException(String.format("predecessor & successor can't be the same: %s", pred));
        }
        HashSet<String> p = preds.get(succ);
        HashSet<String> s = succs.get(pred);
        if ((p == null) || (s == null)) {
            throw new IllegalArgumentException(String.format("Not a successor of anything: %s", succ));
        }
        p.remove(pred);
        s.remove(succ);
    }

    public boolean isStep(String step) {
        return preds.containsKey(step) || succs.containsKey(step) || nodes.contains(step);
    }

    public List<String> steps(String upto) {
        List<String> result = new ArrayList<>();
        List<String> todo = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();

        todo.add(upto);
        while (!todo.isEmpty()) {
            String step = todo.remove(0);
            if (seen.contains(step)) {
                if (!step.equals(upto)) {
                    result.remove(step);
                    result.add(step);
                }
            }
            else {
                seen.add(step);
                result.add(step);
                HashSet<String> p = preds.getOrDefault(step, EMPTY_SET);
                todo.addAll(p);
            }
        }
        return result;
    }
}
