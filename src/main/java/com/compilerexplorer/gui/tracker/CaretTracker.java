package com.compilerexplorer.gui.tracker;

import com.compilerexplorer.common.PathNormalizer;
import com.compilerexplorer.datamodel.CompiledText;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class CaretTracker {
    @NotNull
    private final Consumer<List<CompiledText.SourceLocation>> locationsConsumer;
    @NotNull
    private List<CompiledText.SourceLocation> locations = new ArrayList<>();

    public CaretTracker(@NotNull Consumer<List<CompiledText.SourceLocation>> locationsConsumer_) {
        locationsConsumer = locationsConsumer_;
    }

    public void update(@NotNull VirtualFile file, @NotNull List<Caret> carets) {
        List<CompiledText.SourceLocation> newLocations = collectLocations(file, carets);
        if (!newLocations.equals(locations)) {
            locations = newLocations;
            locationsConsumer.accept(locations);
        }
    }

    @NotNull
    private List<CompiledText.SourceLocation> collectLocations(@NotNull VirtualFile file, @NotNull List<Caret> carets) {
        return carets.stream().
                map(c -> new CompiledText.SourceLocation(PathNormalizer.normalizePath(file.getPath()), c.getLogicalPosition().line + 1)).
                collect(Collectors.toList());
    }

    @NotNull
    public List<CompiledText.SourceLocation> getLocations() {
        return locations;
    }
}
