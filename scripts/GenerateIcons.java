package scripts;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class GenerateIcons {
    public static void main(String[] args) {
        try {
            File outDir = new File("frontend/assets");
            if (!outDir.exists()) outDir.mkdirs();

            renderIcon(192, new File(outDir, "icon-192.png"));
            renderIcon(512, new File(outDir, "icon-512.png"));
            System.out.println("Generated icon-192.png and icon-512.png successfully!");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void renderIcon(int size, File outFile) throws Exception {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        double scale = size / 512.0;
        g2.scale(scale, scale);

        // Background Gradient
        GradientPaint bgGrad = new GradientPaint(0, 0, new Color(0x0b, 0x0f, 0x19), 512, 512, new Color(0x18, 0x12, 0x2B));
        g2.setPaint(bgGrad);
        RoundRectangle2D bgShield = new RoundRectangle2D.Double(24, 24, 464, 464, 112, 112);
        g2.fill(bgShield);

        // Outer Border
        g2.setColor(new Color(0x33, 0x41, 0x55));
        g2.setStroke(new BasicStroke(6f));
        g2.draw(bgShield);

        // Inner Subtle Border
        RoundRectangle2D innerShield = new RoundRectangle2D.Double(40, 40, 432, 432, 96, 96);
        g2.setColor(new Color(99, 102, 241, 64)); // rgba(99, 102, 241, 0.25)
        g2.setStroke(new BasicStroke(2f));
        g2.draw(innerShield);

        // Primary Gradient for Left & Right Pillars (cyan -> indigo -> purple)
        GradientPaint primaryGrad = new GradientPaint(144, 140, new Color(0x06, 0xb6, 0xd4), 368, 372, new Color(0xa8, 0x55, 0xf7));

        // Accent Gradient for Diagonal Beam (sky -> purple)
        GradientPaint accentGrad = new GradientPaint(148, 372, new Color(0x38, 0xbd, 0xf8), 336, 140, new Color(0xc0, 0x84, 0xfc));

        // Left Pillar
        RoundRectangle2D leftPillar = new RoundRectangle2D.Double(144, 140, 44, 232, 16, 16);
        g2.setPaint(primaryGrad);
        g2.fill(leftPillar);

        // Diagonal Energy Beam
        Path2D diag = new Path2D.Double();
        diag.moveTo(176, 140);
        diag.lineTo(336, 348);
        diag.lineTo(336, 372);
        diag.lineTo(292, 372);
        diag.lineTo(148, 184);
        diag.lineTo(148, 140);
        diag.closePath();
        g2.setPaint(accentGrad);
        g2.fill(diag);

        // Right Pillar
        RoundRectangle2D rightPillar = new RoundRectangle2D.Double(324, 140, 44, 232, 16, 16);
        g2.setPaint(primaryGrad);
        g2.fill(rightPillar);

        // Glow behind sparkle
        g2.setColor(new Color(56, 189, 248, 120));
        g2.fill(new Ellipse2D.Double(256 - 28, 256 - 28, 56, 56));

        // AI Core Sparkle (Center)
        g2.setColor(new Color(0x38, 0xbd, 0xf8));
        g2.fill(new Ellipse2D.Double(256 - 16, 256 - 16, 32, 32));

        // Sparkle 4-point star polygon
        Path2D star = new Path2D.Double();
        star.moveTo(256, 220);
        star.lineTo(264, 248);
        star.lineTo(292, 256);
        star.lineTo(264, 264);
        star.lineTo(256, 292);
        star.lineTo(248, 264);
        star.lineTo(220, 256);
        star.lineTo(248, 248);
        star.closePath();
        g2.setColor(Color.WHITE);
        g2.fill(star);

        // Glowing Accent Dots
        g2.setColor(new Color(0x38, 0xbd, 0xf8));
        g2.fill(new Ellipse2D.Double(166 - 7, 140 - 7, 14, 14));

        g2.setColor(new Color(0xc0, 0x84, 0xfc));
        g2.fill(new Ellipse2D.Double(346 - 7, 372 - 7, 14, 14));

        g2.dispose();
        ImageIO.write(img, "png", outFile);
    }
}
