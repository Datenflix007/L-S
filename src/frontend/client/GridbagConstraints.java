package frontend.client;
import java.awt.*;

public class GridbagConstraints // erstellt mit KI
{
    public int gridx, gridy;
    public int gridwidth, gridheight;
    public double weightx, weighty;
    public int anchor;
    public int fill;
    public Insets insets;
    public int ipadx, ipady;

    public GridbagConstraints() 
    {
        this.gridx = 0;
        this.gridy = 0;
        this.gridwidth = 1;
        this.gridheight = 1;
        this.weightx = 0.0;
        this.weighty = 0.0;
        this.anchor = GridBagConstraints.CENTER;
        this.fill = GridBagConstraints.NONE;
        this.insets = new Insets(0, 0, 0, 0);
        this.ipadx = 0;
        this.ipady = 0;
    }

    public GridbagConstraints setGrid(int x, int y) {
        this.gridx = x;
        this.gridy = y;
        return this;
    }
    public int getGridX()
    {
        return this.gridx;
    }
    public int getGridY()
    {
        return this.gridy;
    }

    public GridbagConstraints setSize(int w, int h) {
        this.gridwidth = w;
        this.gridheight = h;
        return this;
    }
    public int getGridWidth()
    {
        return this.gridwidth;
    }
    public int getGridHeight()
    {
        return this.gridheight;
    }

    public GridbagConstraints setWeight(double wx, double wy) {
        this.weightx = wx;
        this.weighty = wy;
        return this;
    }
    public double getWeightX()
    {
        return this.weightx;
    }
    public double getWeightY()
    {
        return this.weighty;
    }

    public GridbagConstraints setAnchor(int anchor) {
        this.anchor = anchor;
        return this;
    }
    public int getAnchor()
    {
        return this.anchor;
    }

    public GridbagConstraints setFill(int fill) {
        this.fill = fill;
        return this;
    }
    public int getFill()
    {
        return this.fill;
    }

    public GridbagConstraints setInsets(int top, int left, int bottom, int right) {
        this.insets = new Insets(top, left, bottom, right);
        return this;
    }
    public Insets getInsets()
    {
        return this.insets;
    }

    public GridbagConstraints setIpad(int ipadx, int ipady) {
        this.ipadx = ipadx;
        this.ipady = ipady;
        return this;
    }
    public int getIpadX()
    {
        return this.ipadx;
    }
    public int getIpadY()
    {
        return this.ipady;
    }

    public GridBagConstraints toAWT() //mit KI
    {
        GridBagConstraints gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.gridx = this.gridx;
        gridBagConstraints.gridy = this.gridy;
        gridBagConstraints.gridwidth = this.gridwidth;
        gridBagConstraints.gridheight = this.gridheight;
        gridBagConstraints.weightx = this.weightx;
        gridBagConstraints.weighty = this.weighty;
        gridBagConstraints.anchor = this.anchor;
        gridBagConstraints.fill = this.fill;
        gridBagConstraints.insets = this.insets;
        gridBagConstraints.ipadx = this.ipadx;
        gridBagConstraints.ipady = this.ipady;
        return gridBagConstraints;
    }
}

