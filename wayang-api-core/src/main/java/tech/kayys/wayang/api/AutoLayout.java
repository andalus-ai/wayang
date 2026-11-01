package tech.kayys.wayang.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.bpmn.ProcessModel;
import tech.kayys.wayang.bpmn.SequenceFlow;

/**
 * Left-to-right layered layout of a process: each element sits in the column
 * of its longest path from a source; elements in a column are stacked in
 * document order. Back edges (cycles) are ignored for ranking. Deterministic.
 */
public final class AutoLayout {

    public static final double COLUMN = 220;
    public static final double ROW = 120;
    public static final double MARGIN = 60;

    private AutoLayout() {
    }

    /** @return element id to {x, y} */
    public static Map<String, double[]> layered(ProcessModel m) {
        List<String> ids = new ArrayList<>(m.elements().keySet());
        Map<String, Integer> rank = new HashMap<>();
        ids.forEach(id -> rank.put(id, 0));
        // Bellman-Ford style relaxation, bounded so cycles cannot loop forever.
        for (int pass = 0; pass < ids.size(); pass++) {
            boolean changed = false;
            for (SequenceFlow f : m.flows()) {
                Integer rs = rank.get(f.source());
                Integer rt = rank.get(f.target());
                if (rs != null && rt != null && rt < rs + 1 && rs + 1 <= ids.size()) {
                    rank.put(f.target(), rs + 1);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        Map<Integer, Integer> rowInColumn = new HashMap<>();
        Map<String, double[]> out = new LinkedHashMap<>();
        for (String id : ids) {
            int r = Math.min(rank.get(id), ids.size());
            int row = rowInColumn.merge(r, 1, Integer::sum) - 1;
            out.put(id, new double[] { MARGIN + r * COLUMN, MARGIN + row * ROW });
        }
        return out;
    }
}
