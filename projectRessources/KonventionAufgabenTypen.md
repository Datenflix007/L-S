# Konventionsbuch über interne Darstellung der Aufgabentypen

## Gliederung
[1. Überischt: Aufgabentypen](#aufgabentypen-tabelle)<br />
[2. Template: FormRenderer.java](#template-markdownparser)<br />

## 1. Aufgabentypen-Tabelle
|Darstellung|Aufgabentyp|Erklärung|
|-|-|-|
|#|Test| |
|##|Testaufgabe| |
|[] | Single-Choice | Radio-Input |
|[_] | Multiple-Choice | Checkbox-Input |
|[__] | Textaufgabe | Textfeld-Input |

## 2. Template: MarkdownParser 
**FormRenderer.java** [^1]   <br />
```
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;

public class FormRenderer {

    // Listen zum Speichern der UI-Elemente
    private static final java.util.List<JRadioButton> radioButtons = new ArrayList<>();
    private static final java.util.List<JCheckBox> checkBoxes = new ArrayList<>();
    private static final java.util.List<JTextField> textFields = new ArrayList<>();

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            String input = """
                    # Test
                    ## Testaufgabe 1
                    [] Single-Choice Option A
                    [] Single-Choice Option B
                    ## Testaufgabe 2
                    [] Multiple-Choice Option 1
                    [] Multiple-Choice Option 2
                    [_] Dein Name
                    """;
            createForm(input);
        });
    }

    public static void createForm(String input) {
        JFrame frame = new JFrame("Formular-Renderer");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(500, 500);
        frame.setLayout(new BorderLayout());

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        ButtonGroup radioGroup = new ButtonGroup();

        String[] lines = input.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("# ")) {
                JLabel label = new JLabel(line.substring(2).trim());
                label.setFont(new Font("Arial", Font.BOLD, 20));
                panel.add(label);
            }
            else if (line.startsWith("## ")) {
                JLabel label = new JLabel(line.substring(3).trim());
                label.setFont(new Font("Arial", Font.BOLD, 16));
                panel.add(label);
            }
            else if (line.startsWith("[]") && line.contains("Single-Choice")) {
                JRadioButton radio = new JRadioButton(line.replace("[] Single-Choice", "").trim());
                radioButtons.add(radio);
                radioGroup.add(radio);
                panel.add(radio);
            }
            else if (line.startsWith("[]") && line.contains("Multiple-Choice")) {
                JCheckBox checkBox = new JCheckBox(line.replace("[] Multiple-Choice", "").trim());
                checkBoxes.add(checkBox);
                panel.add(checkBox);
            }
            else if (line.startsWith("[_]")) {
                JTextField textField = new JTextField(20);
                textFields.add(textField);
                JLabel label = new JLabel(line.replace("[_]", "").trim());
                JPanel fieldPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
                fieldPanel.add(label);
                fieldPanel.add(textField);
                panel.add(fieldPanel);
            }
        }

        // Abgeben-Button
        JButton submitButton = new JButton("Abgeben");
        submitButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.out.println("=== Formular-Auswertung ===");

                // Radio-Buttons
                for (JRadioButton rb : radioButtons) {
                    if (rb.isSelected()) {
                        System.out.println("Single-Choice ausgewählt: " + rb.getText());
                    }
                }

                // Checkboxes
                for (JCheckBox cb : checkBoxes) {
                    if (cb.isSelected()) {
                        System.out.println("Multiple-Choice ausgewählt: " + cb.getText());
                    }
                }

                // Textfelder
                for (JTextField tf : textFields) {
                    System.out.println("Eingabe: " + tf.getText());
                }
                System.out.println("============================");
            }
        });

        panel.add(Box.createRigidArea(new Dimension(0, 10)));
        panel.add(submitButton);

        JScrollPane scrollPane = new JScrollPane(panel);
        frame.add(scrollPane, BorderLayout.CENTER);
        frame.setVisible(true);
    }
}

```

[^1]: wurde mithilfe vom ChatGPT erzeugt 