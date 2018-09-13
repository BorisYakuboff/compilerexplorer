package com.compilerexplorer.gui;

import com.compilerexplorer.common.PathNormalizer;
import com.compilerexplorer.common.RefreshSignal;
import com.compilerexplorer.common.SettingsProvider;
import com.compilerexplorer.common.datamodel.*;
import com.compilerexplorer.common.datamodel.state.*;
import com.compilerexplorer.gui.listeners.AllEditorsListener;
import com.compilerexplorer.gui.listeners.EditorCaretListener;
import com.compilerexplorer.gui.listeners.EditorChangeListener;
import com.compilerexplorer.gui.tracker.CaretTracker;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.EditorMarkupModel;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ex.ToolWindowEx;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.ui.ListCellRendererWrapper;
import com.intellij.ui.components.JBTextField;
import javafx.util.Pair;
import org.jetbrains.annotations.NotNull;
import com.jetbrains.cidr.lang.asm.AsmFileType;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.lang.Error;
import java.util.*;
import java.util.List;
import java.util.Timer;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ToolWindowGui {
    private static final long UPDATE_DELAY_MILLIS = 1000;
    @NotNull
    private static final Color HIGHLIGHT_COLOR = JBColor.CYAN;

    @NotNull
    private final Project project;
    @NotNull
    private final JPanel content;
    @NotNull
    private final ComboBox<SourceSettings> projectSettingsComboBox;
    @NotNull
    private final ComboBox<CompilerMatch> matchesComboBox;
    @NotNull
    private final EditorTextField editor;
    @Nullable
    private Consumer<SourceSettings> sourceSettingsConsumer;
    @Nullable
    private Consumer<SourceRemoteMatched> sourceRemoteMatchedConsumer;
    @Nullable
    private Consumer<RefreshSignal> refreshSignalConsumer;
    @Nullable
    private SourceRemoteMatched sourceRemoteMatched;
    @NotNull
    private Timer updateTimer = new Timer();
    private boolean suppressUpdates = false;
    @NotNull
    private final Map<CompiledText.SourceLocation, List<Pair<Integer, Integer>>> locationsFromSourceMap = new HashMap<>();
    @NotNull
    private final SortedMap<Integer, Pair<Integer, CompiledText.SourceLocation>> locationsToSourceMap = new TreeMap<>();
    @NotNull
    private final TextAttributes highlightAttributes = new TextAttributes();
    @NotNull
    private final CaretTracker caretTracker;

    public ToolWindowGui(@NotNull Project project_, @NotNull ToolWindowEx toolWindow) {
        project = project_;
        content = new JPanel(new BorderLayout());

        JPanel headPanel = new JPanel();
        headPanel.setLayout(new BoxLayout(headPanel, BoxLayout.X_AXIS));

        projectSettingsComboBox = new ComboBox<>();
        projectSettingsComboBox.setRenderer(new ListCellRendererWrapper<SourceSettings>() {
            @Override
            public void customize(@Nullable JList list, @Nullable SourceSettings value, int index, boolean isSelected, boolean cellHasFocus) {
                setText((value != null) ? getText(value) : "");
            }
            @NotNull
            private String getText(@NotNull SourceSettings value) {
                return value.getSourceName();
            }
        });
        projectSettingsComboBox.addItemListener(event -> {
            if (!suppressUpdates && event.getStateChange() == ItemEvent.SELECTED) {
                ApplicationManager.getApplication().invokeLater(() -> selectSourceSettings(projectSettingsComboBox.getItemAt(projectSettingsComboBox.getSelectedIndex())));
            }
        });
        headPanel.add(projectSettingsComboBox);

        matchesComboBox = new ComboBox<>();
        matchesComboBox.setRenderer(new ListCellRendererWrapper<CompilerMatch>() {
            @Override
            public void customize(@Nullable JList list, @Nullable CompilerMatch value, int index, boolean isSelected, boolean cellHasFocus) {
                setText((value != null) ? getText(value) : "");
            }
            @NotNull
            private String getText(@NotNull CompilerMatch value) {
                return value.getRemoteCompilerInfo().getName() + (value.getCompilerMatchKind() != CompilerMatchKind.NO_MATCH ? " (" + CompilerMatchKind.asString(value.getCompilerMatchKind()) + ")" : "");
            }
        });
        matchesComboBox.addItemListener(event -> {
            if (!suppressUpdates && event.getStateChange() == ItemEvent.SELECTED) {
                ApplicationManager.getApplication().invokeLater(() -> selectCompilerMatch(matchesComboBox.getItemAt(matchesComboBox.getSelectedIndex())));
            }
        });
        headPanel.add(matchesComboBox);

        JBTextField additionalSwitchesField = new JBTextField(getState().getAdditionalSwitches());
        additionalSwitchesField.setToolTipText("Additional compiler switches");
        additionalSwitchesField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void changedUpdate(DocumentEvent e) {
                // empty
            }
            @Override
            public void removeUpdate(DocumentEvent e) {
                update();
            }
            @Override
            public void insertUpdate(DocumentEvent e) {
                update();
            }
            private void update() {
                getState().setAdditionalSwitches(additionalSwitchesField.getText());
                if (getState().getAutoupdateFromSource()) {
                    schedulePreprocess();
                }
            }
        });
        headPanel.add(additionalSwitchesField);

        JButton recompileButton = new JButton();
        recompileButton.setIcon(AllIcons.Actions.Refresh);
        recompileButton.setToolTipText("Recompile current source");
        recompileButton.addActionListener(e -> preprocess());
        headPanel.add(recompileButton);

        content.add(headPanel, BorderLayout.NORTH);
        JPanel mainPanel = new JPanel(new BorderLayout());
        content.add(mainPanel, BorderLayout.CENTER);
        editor = new EditorTextField(EditorFactory.getInstance().createDocument(""), project, PlainTextFileType.INSTANCE, true, false) {
            @Override
            protected EditorEx createEditor() {
                EditorEx ed = super.createEditor();
                ed.setHorizontalScrollbarVisible(true);
                ed.setVerticalScrollbarVisible(true);
                ((EditorMarkupModel)ed.getMarkupModel()).setErrorStripeVisible(true);
                ed.setViewer(true);
                //ed.getSettings().setLineNumbersShown(true);
                ed.getCaretModel().addCaretListener(new EditorCaretListener(event -> {
                    if (!suppressUpdates && getState().getAutoscrollToSource()) {
                        CompiledText.SourceLocation source = findSourceLocationFromOffset(ed.logicalPositionToOffset(event.getNewPosition()));
                        if (source != null) {
                            VirtualFile file = LocalFileSystem.getInstance().findFileByPath(source.file);
                            if (file != null) {
                                FileEditorManager.getInstance(project).openFile(file, true);
                                Arrays.stream(EditorFactory.getInstance().getAllEditors())
                                        .filter(editor -> {
                                            if (editor.getProject() == project) {
                                                VirtualFile f = FileDocumentManager.getInstance().getFile(editor.getDocument());
                                                return f != null && PathNormalizer.normalizePath(file.getPath()).equals(PathNormalizer.normalizePath(f.getPath()));
                                            }
                                            return false;
                                        })
                                        .forEach(editor -> {
                                            editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(source.line - 1, 0));
                                            editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
                                        });
                            }
                        }
                    }
                }));
                return ed;
            }
        };
        editor.setFont(new Font("monospaced", editor.getFont().getStyle(), editor.getFont().getSize()));
        mainPanel.add(editor, BorderLayout.CENTER);

        new EditorChangeListener(project, unused ->
            ApplicationManager.getApplication().invokeLater(() -> {
                SettingsState state = getState();
                if (state.getEnabled() && state.getAutoupdateFromSource()) {
                    schedulePreprocess();
                }
            })
        , () -> this.suppressUpdates);

        DefaultActionGroup actionGroup = new DefaultActionGroup();

        addToggleAction(actionGroup, "Compile to binary and disassemble the output", this::getFilters, Filters::getBinary, Filters::setBinary, true);
        addToggleAction(actionGroup, "Execute the binary", this::getFilters, Filters::getExecute, Filters::setExecute, true);
        addToggleAction(actionGroup, "Filter unused labels from the output", this::getFilters, Filters::getLabels, Filters::setLabels, true);
        addToggleAction(actionGroup, "Filter all assembler directives from the output", this::getFilters, Filters::getDirectives, Filters::setDirectives, true);
        addToggleAction(actionGroup, "Remove all lines which are only comments from the output", this::getFilters, Filters::getCommentOnly, Filters::setCommentOnly, true);
        addToggleAction(actionGroup, "Trim intra-line whitespace", this::getFilters, Filters::getTrim, Filters::setTrim, true);
        addToggleAction(actionGroup, "Output disassembly in Intel syntax", this::getFilters, Filters::getIntel, Filters::setIntel, true);
        addToggleAction(actionGroup, "Demangle output", this::getFilters, Filters::getDemangle, Filters::setDemangle, true);
        actionGroup.add(new Separator());
        addToggleAction(actionGroup, "Autoscroll to Source", this::getState, SettingsState::getAutoscrollToSource, SettingsState::setAutoscrollToSource, false);
        addToggleAction(actionGroup, "Autoscroll from Source", this::getState, SettingsState::getAutoscrollFromSource, SettingsState::setAutoscrollFromSource, false);
        addToggleAction(actionGroup, "Autoupdate from Source", this::getState, SettingsState::getAutoupdateFromSource, SettingsState::setAutoupdateFromSource, false);

        actionGroup.add(new AnAction("Compiler Explorer Settings...") {
            @Override
            public void actionPerformed(AnActionEvent event) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Compiler Explorer");
            }
            @Override
            public void update(AnActionEvent event) {
                event.getPresentation().setIcon(AllIcons.General.Settings);
            }
        });

        actionGroup.add(new AnAction("Reset Cache and Reload") {
            @Override
            public void actionPerformed(AnActionEvent event) {
                if (refreshSignalConsumer != null) {
                    refreshSignalConsumer.accept(RefreshSignal.RESET);
                }
            }
            @Override
            public void update(AnActionEvent event) {
                event.getPresentation().setIcon(AllIcons.Actions.ForceRefresh);
            }
        });

        toolWindow.setAdditionalGearActions(actionGroup);

        highlightAttributes.setBackgroundColor(HIGHLIGHT_COLOR);
        caretTracker = new CaretTracker(this::highlightLocations);
        new AllEditorsListener(project, caretTracker::update);

        toolWindow.setTitleActions(new AnAction("Scroll from Source") {
            @Override
            public void actionPerformed(AnActionEvent event) {
                highlightLocations(caretTracker.getLocations(), false, true);
            }
            @Override
            public void update(AnActionEvent event) {
                event.getPresentation().setHoveredIcon(AllIcons.General.LocateHover);
                event.getPresentation().setIcon(AllIcons.General.Locate);
                event.getPresentation().setVisible(!getState().getAutoscrollFromSource());
            }
        });
    }

    private <T> void addToggleAction(@NotNull DefaultActionGroup actionGroup, @NotNull String text, Supplier<T> supplier, Function<T, Boolean> getter, BiConsumer<T, Boolean> setter, boolean recompile) {
        actionGroup.add(new ToggleAction(text) {
            @Override
            public boolean isSelected(AnActionEvent event) {
                return getter.apply(supplier.get());
            }
            @Override
            public void setSelected(AnActionEvent event, boolean selected) {
                setter.accept(supplier.get(), selected);
                if (recompile && refreshSignalConsumer != null) {
                    refreshSignalConsumer.accept(RefreshSignal.COMPILE);
                }
            }
        });
    }

    private void schedulePreprocess() {
        scheduleUpdate(this::preprocess);
    }

    private void preprocess() {
        if (refreshSignalConsumer != null) {
            ApplicationManager.getApplication().invokeLater(() -> refreshSignalConsumer.accept(RefreshSignal.PREPROCESS));
        }
    }

    private void scheduleUpdate(@NotNull Runnable runnable) {
        updateTimer.cancel();
        updateTimer = new Timer();
        updateTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                runnable.run();
            }
        }, UPDATE_DELAY_MILLIS);
    }

    private void selectSourceSettings(@NotNull SourceSettings sourceSettings) {
        projectSettingsComboBox.setToolTipText(getSourceTooltip(sourceSettings));
        if (sourceSettingsConsumer != null) {
            sourceSettingsConsumer.accept(sourceSettings);
        }
    }

    @NotNull
    private String getSourceTooltip(@NotNull SourceSettings sourceSettings) {
        return "File: " + sourceSettings.getSourcePath()
                + "\nLanguage: " + sourceSettings.getLanguage().getDisplayName()
                + "\nCompiler: " + sourceSettings.getCompiler().getAbsolutePath()
                + "\nCompiler kind: " + sourceSettings.getCompilerKind()
                + "\nCompiler options: " + String.join(" ", sourceSettings.getSwitches());
    }

    private void selectCompilerMatch(@NotNull CompilerMatch compilerMatch) {
        matchesComboBox.setToolTipText(getMatchTooltip(compilerMatch));
        if (sourceRemoteMatchedConsumer != null && sourceRemoteMatched != null) {
            sourceRemoteMatchedConsumer.accept(new SourceRemoteMatched(sourceRemoteMatched.getSourceCompilerSettings(),
                    new CompilerMatches(compilerMatch, sourceRemoteMatched.getRemoteCompilerMatches().getOtherMatches())));
        }
    }

    @NotNull
    private String getMatchTooltip(@NotNull CompilerMatch compilerMatch) {
        return "Id: " + compilerMatch.getRemoteCompilerInfo().getId()
                + "\nLanguage: " + compilerMatch.getRemoteCompilerInfo().getLanguage()
                + "\nName: " + compilerMatch.getRemoteCompilerInfo().getName()
                + "\nMatch kind: " + CompilerMatchKind.asStringFull(compilerMatch.getCompilerMatchKind());
    }

    public void setSourceSettingsConsumer(@NotNull Consumer<SourceSettings> sourceSettingsConsumer_) {
        sourceSettingsConsumer = sourceSettingsConsumer_;
    }

    public void setSourceRemoteMatchedConsumer(@NotNull Consumer<SourceRemoteMatched> sourceRemoteMatchedConsumer_) {
        sourceRemoteMatchedConsumer = sourceRemoteMatchedConsumer_;
    }

    public void setRefreshSignalConsumer(@NotNull Consumer<RefreshSignal> refreshSignalConsumer_) {
        refreshSignalConsumer = refreshSignalConsumer_;
    }

    @NotNull
    public JComponent getContent() {
        return content;
    }

    @NotNull
    public Consumer<RefreshSignal> asResetSignalConsumer() {
        return refreshSignal -> {
            ApplicationManager.getApplication().assertIsDispatchThread();
            projectSettingsComboBox.removeAllItems();
            projectSettingsComboBox.setToolTipText("");
        };
    }

    @NotNull
    public Consumer<RefreshSignal> asReconnectSignalConsumer() {
        return refreshSignal -> {
            ApplicationManager.getApplication().assertIsDispatchThread();
            matchesComboBox.removeAllItems();
            matchesComboBox.setToolTipText("");
        };
    }

    @NotNull
    public Consumer<RefreshSignal> asRecompileSignalConsumer() {
        return refreshSignal -> {
            ApplicationManager.getApplication().assertIsDispatchThread();
            sourceRemoteMatched = null;
            //showError("");
        };
    }

    @NotNull
    public Consumer<ProjectSettings> asProjectSettingsConsumer() {
        return projectSettings -> {
            ApplicationManager.getApplication().assertIsDispatchThread();
            suppressUpdates = true;
            SourceSettings oldSelection = projectSettingsComboBox.getItemAt(projectSettingsComboBox.getSelectedIndex());
            SourceSettings newSelection = projectSettings.getSettings().stream()
                    .filter(x -> oldSelection != null && x.getSourcePath().equals(oldSelection.getSourcePath()))
                    .findFirst()
                    .orElse(projectSettings.getSettings().size() != 0 ? projectSettings.getSettings().firstElement() : null);
            DefaultComboBoxModel<SourceSettings> model = new DefaultComboBoxModel<>(projectSettings.getSettings());
            model.setSelectedItem(newSelection);
            projectSettingsComboBox.setModel(model);
            if (newSelection == null) {
                projectSettingsComboBox.removeAllItems();
                projectSettingsComboBox.setToolTipText("");
                showError("No source selected");
            } else {
                selectSourceSettings(newSelection);
            }
            suppressUpdates = false;
        };
    }

    @NotNull
    public Consumer<SourceRemoteMatched> asSourceRemoteMatchedConsumer() {
        return sourceRemoteMatched_ -> {
            ApplicationManager.getApplication().assertIsDispatchThread();
            suppressUpdates = true;
            sourceRemoteMatched = sourceRemoteMatched_;
            CompilerMatch chosenMatch = sourceRemoteMatched.getRemoteCompilerMatches().getChosenMatch();
            List<CompilerMatch> matches = sourceRemoteMatched.getRemoteCompilerMatches().getOtherMatches();
            CompilerMatch oldSelection = matchesComboBox.getItemAt(matchesComboBox.getSelectedIndex());
            CompilerMatch newSelection = matches.stream()
                    .filter(x -> oldSelection != null && x.getRemoteCompilerInfo().getId().equals(oldSelection.getRemoteCompilerInfo().getId()))
                    .findFirst()
                    .orElse(!chosenMatch.getRemoteCompilerInfo().getId().isEmpty() ? chosenMatch : (matches.size() != 0 ? matches.get(0) : null));
            DefaultComboBoxModel<CompilerMatch> model = new DefaultComboBoxModel<>(
                    matches.stream()
                            .map(x -> newSelection == null || !newSelection.getRemoteCompilerInfo().getId().equals(x.getRemoteCompilerInfo().getId()) ? x : newSelection)
                            .filter(Objects::nonNull).collect(Collectors.toCollection(Vector::new)));
            model.setSelectedItem(newSelection);
            matchesComboBox.setModel(model);
            if (newSelection == null) {
                matchesComboBox.removeAllItems();
                matchesComboBox.setToolTipText("");
                showError("No compiler selected");
            } else {
                selectCompilerMatch(newSelection);
            }
            suppressUpdates = false;
        };
    }

    @NotNull
    public Consumer<CompiledText> asCompiledTextConsumer() {
        return compiledText -> {
            ApplicationManager.getApplication().assertIsDispatchThread();
            suppressUpdates = true;

            locationsFromSourceMap.clear();
            locationsToSourceMap.clear();
            StringBuilder asmBuilder = new StringBuilder();
            int currentOffset = 0;
            for (CompiledText.CompiledChunk chunk : compiledText.getCompiledResult().asm) {
                if (chunk.text != null) {
                    int nextOffset = currentOffset + chunk.text.length();
                    asmBuilder.append(chunk.text);
                    asmBuilder.append('\n');
                    if (chunk.source != null && chunk.source.file != null) {
                        locationsFromSourceMap.computeIfAbsent(chunk.source, unused -> new ArrayList<>()).add(new Pair<>(currentOffset, nextOffset));
                        locationsToSourceMap.put(currentOffset, new Pair<>(nextOffset, chunk.source));
                    }
                    currentOffset = nextOffset + 1;
                }
            }

            int oldScrollPosition = (editor.getEditor() != null) ? findCurrentScrollPosition(editor.getEditor()) : 0;

            editor.setNewDocumentAndFileType(AsmFileType.INSTANCE, editor.getDocument());
            editor.setText(asmBuilder.toString());
            editor.setEnabled(true);

            if (editor.getEditor() != null) {
                scrollToPosition(editor.getEditor(), oldScrollPosition);
                highlightLocations(caretTracker.getLocations(), true, false);
            }
            suppressUpdates = false;
        };
    }

    @NotNull
    public Consumer<Error> asErrorConsumer() {
        return error -> showError(error.getMessage());
    }

    private void showError(@NotNull String reason) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        suppressUpdates = true;
        locationsFromSourceMap.clear();
        locationsToSourceMap.clear();
        editor.setNewDocumentAndFileType(PlainTextFileType.INSTANCE, editor.getDocument());
        editor.setText(filterOutTerminalEscapeSequences(reason));
        editor.setEnabled(false);
        suppressUpdates = false;
    }

    @NotNull
    private static String filterOutTerminalEscapeSequences(@NotNull String terminalText) {
        return terminalText.replaceAll("\u001B\\[[;\\d]*.", "");
    }

    @NotNull
    private SettingsState getState() {
        return SettingsProvider.getInstance(project).getState();
    }

    @NotNull
    private Filters getFilters() {
        return getState().getFilters();
    }

    private void highlightLocations(@NotNull List<CompiledText.SourceLocation> locations) {
        highlightLocations(locations, true, false);
    }

    private void highlightLocations(@NotNull List<CompiledText.SourceLocation> locations, boolean highlight, boolean forceScroll) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        EditorEx ed = (EditorEx) editor.getEditor();
        if (ed == null) {
            return;
        }

        boolean scroll = forceScroll || getState().getAutoscrollFromSource();
        int currentScrollPosition = scroll ? findCurrentScrollPosition(ed) : -1;
        int closestPosition = -1;
        int closestPositionDistance = -1;

        MarkupModelEx markupModel = ed.getMarkupModel();
        if (highlight) {
            markupModel.removeAllHighlighters();
        }
        for (CompiledText.SourceLocation location : locations) {
            List<Pair<Integer, Integer>> ranges = locationsFromSourceMap.get(location);
            if (ranges != null) {
                for (Pair<Integer, Integer> range : ranges) {
                    if (highlight) {
                        RangeHighlighter highlighter = markupModel.addRangeHighlighter(range.getKey(), range.getValue(), HighlighterLayer.ADDITIONAL_SYNTAX, highlightAttributes, HighlighterTargetArea.LINES_IN_RANGE);
                        highlighter.setErrorStripeMarkColor(highlightAttributes.getBackgroundColor());
                    }
                    if (scroll) {
                        int positionBegin = ed.offsetToXY(range.getKey()).y;
                        int diffBegin = Math.abs(positionBegin - currentScrollPosition);
                        if ((closestPositionDistance < 0) || (diffBegin < closestPositionDistance)) {
                            closestPositionDistance = diffBegin;
                            closestPosition = positionBegin;
                        }
                        int positionEnd = ed.offsetToXY(range.getValue()).y + ed.getLineHeight();
                        int diffEnd = Math.abs(positionEnd - currentScrollPosition);
                        if ((closestPositionDistance < 0) || (diffEnd < closestPositionDistance)) {
                            closestPositionDistance = diffEnd;
                            closestPosition = positionEnd;
                        }
                    }
                }
            }
        }

        if (scroll && (closestPosition >= 0)) {
            scrollToPosition(ed, closestPosition - (ed.getScrollingModel().getVisibleAreaOnScrollingFinished().height / 2));
        }
    }

    private static int findCurrentScrollPosition(@NotNull Editor ed) {
        return ed.getScrollingModel().getVisibleAreaOnScrollingFinished().y;
    }

    private static void scrollToPosition(@NotNull Editor ed, int y) {
        boolean useAnimation = !ed.getScrollingModel().getVisibleAreaOnScrollingFinished().equals(ed.getScrollingModel().getVisibleArea());
        if (!useAnimation) ed.getScrollingModel().disableAnimation();
        ed.getScrollingModel().scrollVertically(y);
        if (!useAnimation) ed.getScrollingModel().enableAnimation();
    }

    @Nullable
    private CompiledText.SourceLocation findSourceLocationFromOffset(int offset) {
        SortedMap<Integer, Pair<Integer, CompiledText.SourceLocation>> headMap = locationsToSourceMap.headMap(offset + 1);
        if (!headMap.isEmpty()) {
            Pair<Integer, CompiledText.SourceLocation> lastValue = headMap.get(headMap.lastKey());
            if (lastValue.getKey() >= offset) {
                return lastValue.getValue();
            }
        }
        return null;
    }
}
