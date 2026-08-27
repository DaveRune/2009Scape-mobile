package net.java.openjdk.cacio.ctc;

import sun.awt.peer.cacio.managed.FullScreenWindowFactory;

import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.image.ColorModel;
import java.awt.image.DirectColorModel;

/**
 * Stock Cacio always reports no display scaling, so anything asking the platform how big to draw
 * gets 1.0. This copy reports the launcher's interface scale instead.
 */
public class CTCGraphicsConfiguration extends GraphicsConfiguration {

    private static final String UI_SCALE_PROPERTY = "pojav.uiScale";

    private CTCGraphicsDevice device;

    CTCGraphicsConfiguration(CTCGraphicsDevice device) {
        this.device = device;
    }

    @Override
    public GraphicsDevice getDevice() {
        return device;
    }

    @Override
    public ColorModel getColorModel() {
        return ColorModel.getRGBdefault();
    }

    @Override
    public ColorModel getColorModel(int transparency) {
        switch (transparency) {
            case Transparency.OPAQUE:
                return new DirectColorModel(24, 0x00ff0000, 0x0000ff00, 0x000000ff);
            case Transparency.BITMASK:
                return new DirectColorModel(25, 0x00ff0000, 0x0000ff00, 0x000000ff, 0x01000000);
            case Transparency.TRANSLUCENT:
                return ColorModel.getRGBdefault();
            default:
                return null;
        }
    }

    @Override
    public AffineTransform getDefaultTransform() {
        double scale = interfaceScale();
        if (scale == 1d) return new AffineTransform();
        return AffineTransform.getScaleInstance(scale, scale);
    }

    @Override
    public AffineTransform getNormalizingTransform() {
        return new AffineTransform();
    }

    @Override
    public Rectangle getBounds() {
        Dimension screen = FullScreenWindowFactory.getScreenDimension();
        return new Rectangle(0, 0, screen.width, screen.height);
    }

    @Override
    public final boolean isTranslucencyCapable() {
        return true;
    }

    private static double interfaceScale() {
        String property = System.getProperty(UI_SCALE_PROPERTY);
        if (property == null) return 1d;
        try {
            return Math.max(1d, Double.parseDouble(property));
        } catch (NumberFormatException e) {
            return 1d;
        }
    }
}
