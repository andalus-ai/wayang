package tech.kayys.wayang.schema.common;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a 2D position with x and y coordinates.
 */
public class Position {
    @JsonProperty("x")
    private double x;

    @JsonProperty("y")
    private double y;

    public Position() {
        // Default constructor for JSON deserialization
    }

    public Position(double x, double y) {
        this.x = x;
        this.y = y;
    }

    // Getters
    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    // Setters
    public void setX(double x) {
        this.x = x;
    }

    public void setY(double y) {
        this.y = y;
    }
}