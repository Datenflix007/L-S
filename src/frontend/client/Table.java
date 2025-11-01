package frontend.client;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import javax.swing.*;
import javax.swing.table.*;

public class Table extends JTable {

    private TableModel tableModel;
    private final boolean WITH_HEADER;

    private int selectionAlpha = 180;

    // Default Visual Params
    private Color rowColor = new Color(245, 245, 245);
    private int rowSpacing = 8;
    private Color gapColor = new Color(240, 240, 240); // surface
    private Color spacingColor = gapColor;            // Farbe für Abstände zwischen Cards
    private int cardRadius = 10;
    private int indent = 10;

    private boolean wrapperInstalled = false;

    // Dark mode map
    private final HashMap<String, Color> darkMode = new HashMap<>();

    // --- NEU: Font / Font-Farbe für Zellen (können per Builder gesetzt werden) ---
    private Font cellFont = null;
    private Color cellFontColor = null;
    // -------------------------------------------------------------------------

    public Table(TableModel tableModel, boolean withHeader) {
        super(tableModel);
        this.tableModel = tableModel;
        this.WITH_HEADER = withHeader;

        // darkMode init
        darkMode.put("background", new Color(16, 20, 20));
        darkMode.put("surface", new Color(27, 31, 31));
        darkMode.put("primaryText", new Color(229, 229, 229));
        darkMode.put("secondaryText", new Color(144, 144, 144));
        darkMode.put("accent", new Color(38, 198, 218));

        this.rowColor = darkMode.get("background");
        this.gapColor = darkMode.get("surface");
        this.spacingColor = this.gapColor;

        initTable();
    }

    private void initTable() {
        // Falls kein Font / FontColor gesetzt wurden, Default-Werte verwenden
        if (cellFont == null) {
            cellFont = getFont(); // Default-Table-Font
        }
        if (cellFontColor == null) {
            cellFontColor = darkMode.get("primaryText");
        }

        if (getTableHeader() != null) {
            getTableHeader().setOpaque(false);
            getTableHeader().setBackground(spacingColor);
            getTableHeader().setForeground(darkMode.get("primaryText"));
            // optional: Header-Font anpassen, damit Kopfzeile zusammenpasst
            getTableHeader().setFont(cellFont);
        }

        if (!WITH_HEADER)
            setTableHeader(null);

        // wende Font & Foreground auf die Tabelle an (wir zeichnen zusätzlich in den Renderern)
        setFont(cellFont);
        setForeground(cellFontColor);

        setOpaque(false);
        setShowGrid(false);
        setIntercellSpacing(new Dimension(0, 0));
        setFillsViewportHeight(true);
        setRowHeight(36);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        getSelectionModel().addListSelectionListener(e -> repaint());

        IndentedCellRenderer renderer = new IndentedCellRenderer(indent, rowSpacing);
        for (int i = 0; i < getColumnCount(); i++) {
            getColumnModel().getColumn(i).setCellRenderer(renderer);
        }

        setRowSelectionAllowed(true);
        setColumnSelectionAllowed(false);

        Color accent = darkMode.get("accent");
        setSelectionBackground(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), selectionAlpha));
        setSelectionForeground(darkMode.get("primaryText"));

        setBackground(spacingColor);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        SwingUtilities.invokeLater(() -> {
            JViewport vp = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
            if (vp != null) {
                // Viewport darf nicht opaque sein — sonst übermalt es die Rundung des ScrollPane
                vp.setBackground(spacingColor);
                vp.setOpaque(false); // <= wichtig: nicht true

                if (!wrapperInstalled) {
                    Component currentView = vp.getView();
                    if (currentView == this) {
                        JPanel innerWrapper = new JPanel(new BorderLayout());
                        innerWrapper.setOpaque(false);

                        // topSpacer darf ebenfalls transparent sein, sonst malt er am oberen Rand eckig
                        JPanel topSpacer = new JPanel();
                        topSpacer.setOpaque(false); // <= wichtig: nicht true
                        topSpacer.setBackground(spacingColor);
                        topSpacer.setPreferredSize(new Dimension(0, rowSpacing));

                        JPanel content = new JPanel(new BorderLayout());
                        content.setOpaque(false);
                        content.setBorder(BorderFactory.createEmptyBorder(0, rowSpacing, 0, rowSpacing));
                        content.add(this, BorderLayout.CENTER);

                        innerWrapper.add(topSpacer, BorderLayout.NORTH);
                        innerWrapper.add(content, BorderLayout.CENTER);

                        vp.setView(innerWrapper);
                        vp.setViewPosition(new Point(0, 0));
                        vp.revalidate();
                        vp.repaint();

                        wrapperInstalled = true;
                    } else {
                        // Sicherstellen: Viewport bleibt transparent
                        vp.setBackground(spacingColor);
                        vp.setOpaque(false);
                    }
                }
            }
        });
    }

    /* ---------------- Setter ---------------- */
    public void setRowColor(Color rowColor) { this.rowColor = rowColor; repaint(); }
    public void setGapColor(Color gapColor) { this.gapColor = gapColor; repaint(); }
    public void setSpacingColor(Color spacingColor) { this.spacingColor = spacingColor; setBackground(spacingColor); repaint(); }
    public void setRowSpacing(int rowSpacing) { this.rowSpacing = rowSpacing; setRowHeight(36 + rowSpacing/2); repaint(); }
    public void setCardRadius(int cardRadius) { this.cardRadius = cardRadius; repaint(); }
    public void setIndent(int indent) { this.indent = indent; repaint(); }
    public void setSelectionAlpha(int alpha) {
        this.selectionAlpha = Math.max(0, Math.min(255, alpha));
        Color accent = darkMode.get("accent");
        setSelectionBackground(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), this.selectionAlpha));
        repaint();
    }
    public void setSelectionColor(Color selectionColor) {
        setSelectionBackground(new Color(selectionColor.getRed(), selectionColor.getGreen(), selectionColor.getBlue(), selectionAlpha));
        repaint();
    }

    // --- NEU: Methoden zum Setzen von Font und Font-Farbe ---
    public void setCellFont(Font font) {
        this.cellFont = font != null ? font : getFont();
        setFont(this.cellFont);
        // Header-Font mit anpassen, falls vorhanden
        if (getTableHeader() != null) getTableHeader().setFont(this.cellFont);
        repaint();
    }

    public void setCellFontColor(Color color) {
        this.cellFontColor = color != null ? color : darkMode.get("primaryText");
        setForeground(this.cellFontColor);
        // ggf. auch Header-Farbe anpassen (optional)
        if (getTableHeader() != null) getTableHeader().setForeground(this.cellFontColor);
        repaint();
    }
    // -----------------------------------------------------------------

    public HashMap<String, Color> getDarkModeMap() { return darkMode; }

    @Override
    protected void paintComponent(Graphics g) {
        // Hintergrund mit spacing-Farbe füllen (sichtbare Gaps)
        Graphics2D gBg = (Graphics2D) g.create();
        try {
            gBg.setColor(spacingColor);
            gBg.fillRect(0, 0, getWidth(), getHeight());
        } finally {
            gBg.dispose();
        }

        super.paintComponent(g);

        int rowCount = getRowCount();
        if (rowCount == 0) return;

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            float strokeWidth = 1.5f;
            g2.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            Rectangle clip = g.getClipBounds();

            Color defaultHighlight = new Color(255, 255, 255, 40);
            Color defaultShadow = new Color(0, 0, 0, 80);

            Color accentBase = darkMode.get("accent");
            Color turquoise = new Color(accentBase.getRed(), accentBase.getGreen(), accentBase.getBlue(), selectionAlpha);

            for (int row = 0; row < rowCount; row++) {
                Rectangle left = getCellRect(row, 0, true);
                Rectangle right = getCellRect(row, Math.max(0, getColumnCount() - 1), true);

                int x = left.x;
                int y = left.y;
                int x2 = right.x + right.width;
                int fullW = x2 - x;
                int fullH = left.height;

                int h = Math.max(0, fullH - rowSpacing);
                if (h <= 0 || fullW <= 0) continue;

                Rectangle rowRect = new Rectangle(x, y, fullW, h);
                if (!rowRect.intersects(clip)) continue;

                int pad = (int) Math.ceil(strokeWidth / 2.0);
                x += pad;
                y += pad;
                int w = Math.max(0, fullW - pad * 2);
                h = Math.max(0, h - pad * 2);

                if (w <= 0 || h <= 0) continue;

                boolean isSel = isRowSelected(row);
                Color highlight = isSel ? turquoise : defaultHighlight;
                Color shadow = isSel
                        ? new Color(
                            Math.max(0, (int)(turquoise.getRed() * 0.6)),
                            Math.max(0, (int)(turquoise.getGreen() * 0.6)),
                            Math.max(0, (int)(turquoise.getBlue() * 0.6)),
                            turquoise.getAlpha()
                          )
                        : defaultShadow;

                int off = 1;
                g2.setColor(shadow);
                g2.drawRoundRect(x + off, y + off, w - 1 - off, h - 1 - off, cardRadius, cardRadius);

                g2.setColor(highlight);
                g2.drawRoundRect(x, y, w - 1, h - 1, cardRadius, cardRadius);

                Color inner = new Color(255, 255, 255, 20);
                g2.setColor(inner);
                int inset = 2;
                int innerW = Math.max(0, w - 1 - inset * 2);
                int innerH = Math.max(0, h - 1 - inset * 2);
                int innerRadius = Math.max(0, cardRadius - inset);
                if (innerW > 0 && innerH > 0) {
                    g2.drawRoundRect(x + inset, y + inset, innerW, innerH, innerRadius, innerRadius);
                }
            }
        } finally {
            g2.dispose();
        }
    }

    /* ---------------- TableModel ---------------- */
    public static class TableModel extends AbstractTableModel {
        private ArrayList<String> columnNames = new ArrayList<>();
        private ArrayList<Object[]> data = new ArrayList<>();

        public TableModel(ArrayList<String> columnNames, ArrayList<Object[]> data) {
            this.columnNames = columnNames;
            this.data = data;
        }

        @Override public int getRowCount() { return data.size(); }
        @Override public int getColumnCount() { return columnNames.size(); }
        @Override public String getColumnName(int columnIndex) { return columnNames.get(columnIndex); }
        @Override public Object getValueAt(int rowIndex, int columnIndex) { return data.get(rowIndex)[columnIndex]; }
        @Override public void setValueAt(Object value, int rowIndex, int columnIndex) {
            data.get(rowIndex)[columnIndex] = value; fireTableCellUpdated(rowIndex,columnIndex);
        }
        public void addNewRow(Object[] newRow) { data.add(newRow); fireTableRowsInserted(data.size()-1,data.size()-1); }
        public void removeRow(int rowIndex) { if(rowIndex>=0 && rowIndex<data.size()){ data.remove(rowIndex); fireTableRowsDeleted(rowIndex,rowIndex);} }
        public void setNewData(ArrayList<Object[]> newData) { data.clear(); data.addAll(newData); fireTableDataChanged(); }
    }

    /* ---------------- Builder ---------------- */
    public static class Builder {
        private ArrayList<String> columnNames = new ArrayList<>();
        private ArrayList<Object[]> data = new ArrayList<>();
        private boolean withHeader = true;
        private int rowSpacing = 8;
        private int cardRadius = 10;
        private int indent = 10;
        private Color rowColor = new Color(245, 245, 245);
        private Color gapColor = new Color(240, 240, 240);
        private Color spacingColor = gapColor;

        // --- NEU: Builder-Felder für Font & Font-Farbe ---
        private Font font = null;
        private Color fontColor = null;
        // ---------------------------------------------------

        public Builder addRow(Object[] newRow) { data.add(newRow); return this; }
        public Builder addColumn(String columnName) { columnNames.add(columnName); return this; }
        public Builder withHeader(boolean withHeader) { this.withHeader = withHeader; return this; }
        public Builder setRowSpacing(int rowSpacing) { this.rowSpacing = rowSpacing; return this; }
        public Builder setCardRadius(int cardRadius) { this.cardRadius = cardRadius; return this; }
        public Builder setIndent(int indent) { this.indent = indent; return this; }
        public Builder setRowColor(Color rowColor) { this.rowColor = rowColor; return this; }
        public Builder setGapColor(Color gapColor) { this.gapColor = gapColor; return this; }
        public Builder setSpacingColor(Color spacingColor) { this.spacingColor = spacingColor; return this; }

        // --- NEU: Builder-Methoden ---
        public Builder setFont(Font font) { this.font = font; return this; }
        public Builder setFontColor(Color color) { this.fontColor = color; return this; }
        // ----------------------------

        public Table build() {
            TableModel tableModel = new TableModel(columnNames, data);
            Table table = new Table(tableModel, withHeader);
            table.setRowSpacing(rowSpacing);
            table.setCardRadius(cardRadius);
            table.setIndent(indent);
            table.setRowColor(rowColor);
            table.setGapColor(gapColor);
            table.setSpacingColor(spacingColor);

            // Übergabe von Builder-Font / Font-Farbe an die Table-Instanz
            if (this.font != null) table.setCellFont(this.font);
            if (this.fontColor != null) table.setCellFontColor(this.fontColor);

            return table;
        }
    }

    /* ---------------- Renderer ---------------- */
    private class IndentedCellRenderer extends DefaultTableCellRenderer {
        private final int spacing;
        private int currentColumn = -1;

        public IndentedCellRenderer(int indent, int spacing) {
            Table.this.indent = indent;
            this.spacing = spacing;
            setOpaque(false);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            this.currentColumn = column;

            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, false, hasFocus, row, column);

            int topPadding = 4;
            int bottomPadding = Math.max(0, spacing - topPadding);
            int leftPadding = Table.this.indent;
            int rightPadding = 8;

            label.setBorder(BorderFactory.createEmptyBorder(topPadding, leftPadding, bottomPadding, rightPadding));
            label.setHorizontalAlignment(SwingConstants.LEFT);
            label.setVerticalAlignment(SwingConstants.CENTER);

            // Verwende hier das konfigurierte Font + Font-Farbe
            if (Table.this.cellFont != null) label.setFont(Table.this.cellFont);
            if (Table.this.cellFontColor != null) label.setForeground(Table.this.cellFontColor);

            label.setBackground(rowColor);
            label.setOpaque(false);
            //label.setForeground(darkMode.get("primaryText"));  // entfällt, da wir cellFontColor nutzen

            return label;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight() - spacing;
            if (h < 0) h = getHeight();

            int colCount = Table.this.getColumnCount();
            boolean singleColumn = (colCount == 1);
            boolean isFirst = (currentColumn == 0);
            boolean isLast = (currentColumn == colCount - 1);

            g2.setColor(rowColor);

            if (singleColumn) {
                g2.fillRoundRect(0, 0, w, h, cardRadius, cardRadius);
            } else if (isFirst) {
                g2.fillRoundRect(0, 0, w, h, cardRadius, cardRadius);
                g2.fillRect(w/2, 0, w - w/2, h);
            } else if (isLast) {
                g2.fillRoundRect(0, 0, w, h, cardRadius, cardRadius);
                g2.fillRect(0, 0, w/2, h);
            } else {
                g2.fillRect(0, 0, w, h);
            }

            g2.dispose();

            Graphics2D gText = (Graphics2D) g.create();
            try {
                if (spacing > 0) {
                    double dy = - (spacing / 2.0);
                    gText.translate(0, dy);
                }
                super.paintComponent(gText);
            } finally {
                gText.dispose();
            }
        }
    }
}
