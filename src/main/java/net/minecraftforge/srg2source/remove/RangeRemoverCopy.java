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

package net.minecraftforge.srg2source.remove;

import net.minecraftforge.srg2source.api.InputSupplier;
import net.minecraftforge.srg2source.api.OutputSupplier;
import net.minecraftforge.srg2source.apply.ClassMeta;
import net.minecraftforge.srg2source.apply.ExceptorClass;
import net.minecraftforge.srg2source.apply.RangeApplier;
import net.minecraftforge.srg2source.range.RangeMap;
import net.minecraftforge.srg2source.range.entries.StructuralEntry;
import net.minecraftforge.srg2source.util.Util;
import net.minecraftforge.srgutils.IMappingFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public class RangeRemoverCopy extends RangeApplier {
    private static Pattern IMPORT = Pattern.compile("import\\s+((?<static>static)\\s+)?(?<class>[A-Za-z][A-Za-z0-9_\\.]*\\*?);.*");

    private List<IMappingFile> srgs = new ArrayList<>();
    private Map<String, String> clsSrc2Internal = new HashMap<>();
    private Map<String, ExceptorClass> excs = Collections.emptyMap();
    private boolean keepImports = false; // Keep imports that are not referenced anywhere in code.
    private InputSupplier input = null;
    private OutputSupplier output = null;
    private Map<String, RangeMap> range = new HashMap<>();
    private ClassMeta meta = null;
    private Map<String, String> guessLambdas = null;
    private boolean guessLocals = false;
    private boolean sortImports = false;

    public void readSrg(Path srg) {
        try (InputStream in = Files.newInputStream(srg)) {
            IMappingFile map = IMappingFile.load(in);
            srgs.add(map); //TODO: Add merge function to SrgUtils?

            map.getClasses().forEach(cls -> {
                clsSrc2Internal.put(cls.getOriginal().replace('/', '.').replace('$', '.'), cls.getOriginal());

                if (guessLambdas != null) {
                    cls.getMethods().stream()
                    .filter(Objects::nonNull)
                    .flatMap(mtd -> mtd.getParameters().stream())
                    .filter(Objects::nonNull)
                    .forEach(p -> this.guessLambdas.put(p.getOriginal(), p.getMapped()));
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read SRG: " + srg, e);
        }
    }

    public void readExc(Path value) {
        readExc(value, StandardCharsets.UTF_8);
    }

    public void readExc(Path value, Charset encoding) {
        try {
            this.excs = ExceptorClass.create(value, encoding, this.excs);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read EXC: " + value, e);
        }
    }

    public void setGuessLambdas(boolean value) {
        if (!value) {
            this.guessLambdas = null;
        } else {
            this.guessLambdas = new HashMap<>();
            this.srgs.stream()
                .flatMap(srg -> srg.getClasses().stream())
                .flatMap(cls -> cls.getMethods().stream())
                .flatMap(mtd -> mtd.getParameters().stream())
                .forEach(p -> this.guessLambdas.put(p.getOriginal(), p.getMapped()));
        }
    }

    public void setGuessLocals(boolean value) {
        this.guessLocals = value;
    }

    public void setSortImports(boolean value) {
        this.sortImports = value;
    }

    public void setInput(InputSupplier value) {
        this.input = value;
    }

    public void setOutput(OutputSupplier value) {
        this.output = value;
    }

    public void readRangeMap(File value) {
        try (InputStream in = Files.newInputStream(value.toPath())) {
            this.range.putAll(RangeMap.readAll(in));
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid range map: " + value);
        }
    }

    public void readRangeMap(Path value) {
        try (InputStream in = Files.newInputStream(value)) {
            this.range.putAll(RangeMap.readAll(in));
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid range map: " + value);
        }
    }

    public void keepImports(boolean value) {
        this.keepImports = value;
    }

    public void run() throws IOException {
        if (input == null)
            throw new IllegalStateException("Missing Range Apply input");
        if (output == null)
            throw new IllegalStateException("Missing Range Apply output");
        if (range == null)
            throw new IllegalStateException("Missing Range Apply range");

        //meta = ClassMeta.create(this, range);

        List<String> paths = new ArrayList<>(range.keySet());
        Collections.sort(paths);

        log("Range remover!");
        log("Processing " + paths.size() + " files");

        for (String filePath : paths) {
            log("Start Processing: " + filePath);
            InputStream stream = input.getInput(filePath);

            //no stream? what?
            if (stream == null) {
                // yeah.. nope.
                log("Data not found: " + filePath);
                continue;
            }
            Charset encoding = input.getEncoding(filePath);
            if (encoding == null)
                encoding = StandardCharsets.UTF_8;

            String data = new String(Util.readStream(stream), encoding);
            stream.close();

            // process
            List<String> out = processJavaSourceFile(filePath, data, range.get(filePath), meta);
            filePath = out.get(0);
            data = out.get(1);

            // write.
            if (data != null) {
                OutputStream outStream = output.getOutput(filePath);
                if (outStream == null)
                    throw new IllegalStateException("Could not get output stream form: " + filePath);
                outStream.write(data.getBytes(encoding));
                outStream.close();
            }

            log("End  Processing: " + filePath);
            log("");
        }

        output.close();
    }

    private List<String> processJavaSourceFile(String fileName, String data, RangeMap rangeList, ClassMeta meta) throws IOException {
        StringBuilder outData = new StringBuilder();
        outData.append(data);

        Set<String> importsToAdd = new TreeSet<>();
        int shift = 0;

        for (StructuralEntry structure : rangeList.getRoot().getStructures(true)
                .stream().sorted(Comparator.comparing(StructuralEntry::getStart)).collect(Collectors.toList())) {

            int start = structure.getStart();
            int end = start + structure.getLength();

            List<StructuralEntry> entries = structure.getStructures(false);

            List<StructuralEntry> annotations = entries.stream()
                    .filter(e -> e.getType() == StructuralEntry.Type.ANNOTATION)
                    .collect(Collectors.toList());

            //System.out.println("  annotations: " + annotations.size() + annotations);

            List<StructuralEntry> distAnnotations = annotations.stream()
                    .filter(e -> e.getName().equalsIgnoreCase("net.minecraftforge.api.distmarker.OnlyIn"))
                    .collect(Collectors.toList());

            if (distAnnotations.isEmpty())
                continue;

            switch (structure.getType()) {
                case ROOT:
                case CLASS:
                case INTERFACE:
                    return Arrays.asList(fileName, null);
                case ENUM:
                case METHOD:
                case FIELD:
                case ANNOTATION:
                case RECORD:
                    break;
            }

            log("Remove " + structure + " Shift[" + shift + "]");

            // Remove algorithm:
            // 1. find distance to last line end
            int startDistance = 0;
            while (outData.charAt(start + shift - startDistance) != '\n') {
                startDistance++;
            }
            if (outData.charAt(start + shift - startDistance - 1) == '\r') // Catch CRLF
                startDistance++;
            // 2. find distance to next line end if removing method
            int endDistance = 0;
            if (structure.getType() == StructuralEntry.Type.METHOD) {
                while (outData.charAt(end + shift + endDistance - 1) != '\n') {
                    endDistance++;
                }
            }
            // 3. textually replace text at specified range with nothing
            outData.replace(start + shift - startDistance, end + shift + endDistance, "");
            // 4. shift future ranges by difference in text length
            shift -= structure.getLength() + startDistance + endDistance;
        }

        return Arrays.asList(fileName, outData.toString());
    }
}
