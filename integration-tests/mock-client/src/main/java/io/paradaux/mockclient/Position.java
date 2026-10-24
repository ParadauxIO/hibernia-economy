package io.paradaux.mockclient;

/**
 * A player's position and look angles. Coordinates are in Minecraft world space
 * (continuous doubles). yaw is rotation around the Y axis (degrees, -180..180);
 * pitch is rotation up/down (degrees, -90..90).
 */
public record Position(double x, double y, double z, float yaw, float pitch) {

    public static Position of(double x, double y, double z) {
        return new Position(x, y, z, 0f, 0f);
    }

    public double distanceTo(Position other) {
        double dx = other.x - x;
        double dy = other.y - y;
        double dz = other.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public boolean within(Position target, double radius) {
        return distanceTo(target) <= radius;
    }
}
