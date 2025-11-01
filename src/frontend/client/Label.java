package frontend.client;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.Border;

public class Label extends JLabel
{
    private final Dimension MINIMUM_SIZE, PREFERRED_SIZE, MAXIMUM_SIZE;
    private final Color FOREGROUND_COLOR, BACKGROUND_COLOR;
    private final Border BORDER;
    private final Font FONT;
    private String labelText;
    private final float ALIGNMENT_X, ALIGNMENT_Y;

    private Label (Builder builder)
    {
        this.MINIMUM_SIZE = builder.minimumSize;
        this.PREFERRED_SIZE = builder.preferredSize;
        this.MAXIMUM_SIZE = builder.maximumSize;
        this.FOREGROUND_COLOR = builder.forgroundColor;
        this.BACKGROUND_COLOR = builder.backgroundColor;
        this.BORDER = builder.border;
        this.FONT = builder.font;
        this.labelText = builder.labelText;
        this.ALIGNMENT_X = builder.alignmentX;
        this.ALIGNMENT_Y = builder.alignmentY;
        

        if (MINIMUM_SIZE != null)
            super.setMinimumSize(MINIMUM_SIZE);
        if (PREFERRED_SIZE != null)
            super.setPreferredSize(PREFERRED_SIZE);
        if (MAXIMUM_SIZE != null)
            super.setMaximumSize(MAXIMUM_SIZE);
        if (FOREGROUND_COLOR != null)
            super.setForeground(FOREGROUND_COLOR);
        if (BACKGROUND_COLOR != null)
            super.setBackground(BACKGROUND_COLOR);
        if (BORDER != null)
            super.setBorder(BORDER);
        if (FONT != null)
            super.setFont(FONT);
        if (labelText != null)
            super.setText(this.labelText);
        if (ALIGNMENT_X == 0.0f || ALIGNMENT_X == 0.5f || ALIGNMENT_X == 1.0f)
            super.setAlignmentX(ALIGNMENT_X);
        if (ALIGNMENT_Y == 0.0f || ALIGNMENT_Y == 0.5f || ALIGNMENT_Y == 1.0f)
            super.setAlignmentY(ALIGNMENT_Y);
    }

    public void setLabelText(String labelText)
    {
        this.labelText = labelText;
    }
    public String getLabelText()
    {
        return this.labelText;
    }

    @Override
    public String toString()
    {
        return "Label{" + System.lineSeparator() +
            ", minimumSize     = " + (MINIMUM_SIZE != null ? MINIMUM_SIZE.toString() : "null") + System.lineSeparator() +
            ", preferredSize   = " + (PREFERRED_SIZE != null ? PREFERRED_SIZE.toString() : "null") + System.lineSeparator() +
            ", maximumSize     = " + (MAXIMUM_SIZE != null ? MAXIMUM_SIZE.toString() : "null") + System.lineSeparator() +
            ", foregroundColor = " + (FOREGROUND_COLOR != null ? FOREGROUND_COLOR.toString() : "null") + System.lineSeparator() +
            ", backgroundColor = " + (BACKGROUND_COLOR != null ? BACKGROUND_COLOR.toString() : "null") + System.lineSeparator() +
            ", border          = " + (BORDER != null ? BORDER.getClass().getSimpleName() : "null") + System.lineSeparator() +
            ", font            = " + (FONT != null ? FONT.getClass().getSimpleName() : "null") + System.lineSeparator() +
            ", LabelText       = " + (labelText != null ? labelText : "null") + System.lineSeparator() +
            '}';
    }

    public static class Builder
    {
        private Dimension minimumSize, preferredSize, maximumSize;
        private Color forgroundColor, backgroundColor;
        private Border border;
        private Font font;
        private String labelText;
        private float alignmentX, alignmentY;

        public Builder minimumSize(Dimension minimumSize)
        {
            this.minimumSize = minimumSize;
            return this;
        }

        public Builder preferredSize(Dimension preferredSize)
        {
            this.preferredSize = preferredSize;
            return this;
        }

        public Builder maximumSize(Dimension maxiumSize)
        {
            this.maximumSize = maxiumSize;
            return this;
        }

        public Builder foregroundColor(Color foregroundColor)
        {
            this.forgroundColor = foregroundColor;
            return this;
        }

        public Builder backgroundColor(Color backgroundColor)
        {
            this.backgroundColor = backgroundColor;
            return this;
        }

        public Builder border(Border border)
        {
            this.border = border;
            return this;
        }

        public Builder font(Font font)
        {
            this.font = font;
            return this;
        }

        public Builder alignmentX(float alignment)
        {
            this.alignmentX = alignment;
            return this;
        }

        public Builder alignmentY(float alignment)
        {
            this.alignmentY = alignment;
            return this;
        }

        public Builder labelText(String labelText)
        {
            this.labelText = labelText;
            return this;
        }

        public Label build()
        {
            return new Label(this);
        }
    }
}

