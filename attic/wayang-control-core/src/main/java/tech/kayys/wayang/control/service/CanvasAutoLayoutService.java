package tech.kayys.wayang.control.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.wayang.schema.canvas.CanvasData;
import tech.kayys.wayang.schema.canvas.CanvasEdge;
import tech.kayys.wayang.schema.canvas.CanvasNode;
import tech.kayys.wayang.schema.layout.LayoutAlgorithm;
import tech.kayys.wayang.schema.layout.Position;

/**
 * Automatic layout and beautification for the canvas.
 */
@ApplicationScoped
public class CanvasAutoLayoutService {

    private static final Logger LOG = LoggerFactory.getLogger(CanvasAutoLayoutService.class);

    /**
     * Apply auto-layout to canvas.
     */
    public Uni<CanvasData> autoLayout(CanvasData canvas, LayoutAlgorithm algorithm) {
        return Uni.createFrom().item(() -> {
            switch (algorithm) {
                case HIERARCHICAL -> applyHierarchicalLayout(canvas);
                case FORCE_DIRECTED -> applyForceDirectedLayout(canvas);
                case GRID -> applyGridLayout(canvas);
                case SWIMLANE -> applySwimlaneLayout(canvas);
                default -> LOG.warn("Unknown layout algorithm: {}", algorithm);
            }
            return canvas;
        });
    }

    private void applyHierarchicalLayout(CanvasData canvas) {
        Map<String, Integer> ranks = calculateRanks(canvas);
        Map<Integer, List<CanvasNode>> rankGroups = new HashMap<>();

        canvas.nodes.forEach(node -> {
            int rank = ranks.getOrDefault(node.id, 0);
            rankGroups.computeIfAbsent(rank, k -> new ArrayList<>()).add(node);
        });

        int yOffset = 50;
        int rankSpacing = 150;
        int nodeSpacing = 100;

        for (int rank = 0; rank < rankGroups.size(); rank++) {
            List<CanvasNode> nodesInRank = rankGroups.get(rank);
            if (nodesInRank == null)
                continue;

            int totalWidth = nodesInRank.size() * 200 + (nodesInRank.size() - 1) * nodeSpacing;
            int xOffset = -totalWidth / 2;

            for (int i = 0; i < nodesInRank.size(); i++) {
                CanvasNode node = nodesInRank.get(i);
                node.position = new Position(xOffset + i * (200 + nodeSpacing), (double) yOffset + rank * rankSpacing);
            }
        }
    }

    private Map<String, Integer> calculateRanks(CanvasData canvas) {
        Map<String, Integer> ranks = new HashMap<>();
        List<String> startNodes = canvas.nodes.stream()
                .filter(n -> "START".equalsIgnoreCase(n.type))
                .map(n -> n.id)
                .toList();

        Queue<String> queue = new LinkedList<>(startNodes);
        startNodes.forEach(id -> ranks.put(id, 0));
        Map<String, List<String>> adjacency = buildAdjacencyList(canvas);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentRank = ranks.get(current);

            List<String> neighbors = adjacency.getOrDefault(current, List.of());
            for (String neighbor : neighbors) {
                int newRank = currentRank + 1;
                if (!ranks.containsKey(neighbor) || ranks.get(neighbor) < newRank) {
                    ranks.put(neighbor, newRank);
                    queue.offer(neighbor);
                }
            }
        }
        return ranks;
    }

    private Map<String, List<String>> buildAdjacencyList(CanvasData canvas) {
        Map<String, List<String>> adjacency = new HashMap<>();
        canvas.edges.forEach(edge -> adjacency.computeIfAbsent(edge.source, k -> new ArrayList<>()).add(edge.target));
        return adjacency;
    }

    private void applyForceDirectedLayout(CanvasData canvas) {
        int iterations = 100;
        double repulsionForce = 1000;
        double attractionForce = 0.1;
        double damping = 0.9;

        Map<String, Velocity> velocities = new HashMap<>();
        canvas.nodes.forEach(node -> {
            if (node.position == null) {
                node.position = new Position(Math.random() * 1000, Math.random() * 1000);
            }
            velocities.put(node.id, new Velocity());
        });

        for (int iter = 0; iter < iterations; iter++) {
            for (CanvasNode node1 : canvas.nodes) {
                Velocity v1 = velocities.get(node1.id);
                for (CanvasNode node2 : canvas.nodes) {
                    if (node1.id.equals(node2.id))
                        continue;

                    double dx = node1.position.x - node2.position.x;
                    double dy = node1.position.y - node2.position.y;
                    double distance = Math.sqrt(dx * dx + dy * dy);
                    if (distance < 1)
                        distance = 1;

                    double force = repulsionForce / (distance * distance);
                    v1.dx += (dx / distance) * force;
                    v1.dy += (dy / distance) * force;
                }
            }

            for (CanvasEdge edge : canvas.edges) {
                Velocity v1 = velocities.get(edge.source);
                Velocity v2 = velocities.get(edge.target);
                if (v1 == null || v2 == null)
                    continue;

                CanvasNode n1 = findNode(canvas, edge.source);
                CanvasNode n2 = findNode(canvas, edge.target);

                double dx = n1.position.x - n2.position.x;
                double dy = n1.position.y - n2.position.y;
                double distance = Math.sqrt(dx * dx + dy * dy);
                if (distance < 1)
                    distance = 1;

                double force = (distance - 200) * attractionForce;
                v1.dx -= (dx / distance) * force;
                v1.dy -= (dy / distance) * force;
                v2.dx += (dx / distance) * force;
                v2.dy += (dy / distance) * force;
            }

            canvas.nodes.forEach(node -> {
                Velocity v = velocities.get(node.id);
                node.position.x += v.dx;
                node.position.y += v.dy;
                v.dx *= damping;
                v.dy *= damping;
            });
        }
    }

    private void applyGridLayout(CanvasData canvas) {
        int cols = (int) Math.ceil(Math.sqrt(canvas.nodes.size()));
        int spacing = 250;
        for (int i = 0; i < canvas.nodes.size(); i++) {
            CanvasNode node = canvas.nodes.get(i);
            node.position = new Position((double) (i % cols) * spacing, (double) (i / cols) * spacing);
        }
    }

    private void applySwimlaneLayout(CanvasData canvas) {
        int yOffset = 50;
        int spacing = 150;
        for (int i = 0; i < canvas.nodes.size(); i++) {
            CanvasNode node = canvas.nodes.get(i);
            node.position = new Position(100.0, (double) yOffset + i * spacing);
        }
    }

    private CanvasNode findNode(CanvasData canvas, String id) {
        return canvas.nodes.stream().filter(n -> n.id.equals(id)).findFirst().orElse(null);
    }

    private static class Velocity {
        double dx = 0;
        double dy = 0;
    }
}
