/*
 * Srg2Source
 * Copyright (c) 2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.srg2source.api;

import net.minecraftforge.srg2source.remove.RangeRemover;
import net.minecraftforge.srg2source.util.io.ChainedInputSupplier;
import net.minecraftforge.srg2source.util.io.FolderSupplier;
import net.minecraftforge.srg2source.util.io.ZipInputSupplier;
import net.minecraftforge.srg2source.util.io.ZipOutputSupplier;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class RangeRemoverBuilder {
    private PrintStream logStd = System.out;
    private PrintStream logErr = System.err;
    private List<InputSupplier> inputs = new ArrayList<>();
    private OutputSupplier output = null;
    private Consumer<RangeRemover> range = null;
    private List<Consumer<RangeRemover>> srgs = new ArrayList<>();
    private List<Consumer<RangeRemover>> excs = new ArrayList<>();
    private boolean keepImports = false;
    private boolean guessLambdas = false;
    private boolean guessLocals = false;
    private boolean sortImports = false;

    public RangeRemoverBuilder logger(PrintStream value) {
        this.logStd = value;
        return this;
    }

    public RangeRemoverBuilder errorLogger(PrintStream value) {
        this.logErr = value;
        return this;
    }

    public RangeRemoverBuilder output(Path value) {
        try {
            if (Files.isDirectory(value))
                this.output = FolderSupplier.create(value, null);
            else
                this.output = new ZipOutputSupplier(value);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid output: " + value, e);
        }
        return this;
    }

    public RangeRemoverBuilder srg(Path value) {
        this.srgs.add(a -> a.readSrg(value));
        return this;
    }


    public RangeRemoverBuilder exc(Path value) {
        this.excs.add(a -> a.readExc(value));
        return this;
    }

    public RangeRemoverBuilder input(Path value) {
        return input(value, StandardCharsets.UTF_8);
    }

    public RangeRemoverBuilder guessLambdas() {
        return guessLambdas(true);
    }

    public RangeRemoverBuilder guessLambdas(boolean value) {
        this.guessLambdas = value;
        return this;
    }

    public RangeRemoverBuilder guessLocals() {
        return guessLocals(true);
    }

    public RangeRemoverBuilder guessLocals(boolean value) {
        this.guessLocals = value;
        return this;
    }

    public RangeRemoverBuilder sortImports() {
        return sortImports(true);
    }

    public RangeRemoverBuilder sortImports(boolean value) {
        this.sortImports = value;
        return this;
    }

    @SuppressWarnings("resource")
    public RangeRemoverBuilder input(Path value, Charset encoding) {
        if (value == null || !Files.exists(value))
            throw new IllegalArgumentException("Invalid input value: " + value);

        String filename = value.getFileName().toString().toLowerCase(Locale.ENGLISH);
        try {
            if (Files.isDirectory(value))
                inputs.add(FolderSupplier.create(value, encoding));
            else if (filename.endsWith(".jar") || filename.endsWith(".zip")) {
                inputs.add(ZipInputSupplier.create(value, encoding));
            } else
                throw new IllegalArgumentException("Invalid input value: " + value);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid input: " + value, e);
        }

        return this;
    }

    public RangeRemoverBuilder input(InputSupplier value) {
        this.inputs.add(value);
        return this;
    }

    public RangeRemoverBuilder range(File value) {
        this.range = a -> a.readRangeMap(value);
        return this;
    }

    public RangeRemoverBuilder range(Path value) {
        this.range = a -> a.readRangeMap(value);
        return this;
    }

    public RangeRemoverBuilder trimImports() {
        this.keepImports = false;
        return this;
    }

    public RangeRemoverBuilder keepImports() {
        this.keepImports = true;
        return this;
    }

    public RangeRemover build() {
        if (output == null)
            throw new IllegalStateException("Builder State Exception: Missing Output");
        if (range == null)
            throw new IllegalArgumentException("Builder State Exception: Missing Range Map");

        RangeRemover ret = new RangeRemover();
        ret.setLogger(logStd);
        ret.setErrorLogger(logErr);

        if (this.inputs.size() == 1)
            ret.setInput(this.inputs.get(0));
        else
            ret.setInput(new ChainedInputSupplier(this.inputs));

        ret.setOutput(output);
        range.accept(ret);

        ret.setGuessLambdas(guessLambdas);
        ret.setGuessLocals(guessLocals);
        ret.setSortImports(sortImports);

        srgs.forEach(e -> e.accept(ret));
        excs.forEach(e -> e.accept(ret));

        ret.keepImports(keepImports);

        return ret;
    }
}
