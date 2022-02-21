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
import net.minecraftforge.srg2source.range.entries.*;
import net.minecraftforge.srg2source.util.Util;
import net.minecraftforge.srgutils.IMappingFile;

import javax.xml.transform.Result;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings("unused")
public class RangeRemover extends RangeApplier {

    public void run() throws IOException {
        if (super.input == null)
            throw new IllegalStateException("Missing Range Apply input");
        if (super.output == null)
            throw new IllegalStateException("Missing Range Apply output");
        if (super.range == null)
            throw new IllegalStateException("Missing Range Apply range");

        List<String> paths = new ArrayList<>(range.keySet());
        Collections.sort(paths);

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
            List<String> out = processJavaSourceFile(filePath, data, range.get(filePath), null);
            filePath = out.get(0);
            data = out.get(1);

            // write.
            if (data != null && data.length() > 0) {
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

    private List<String> processJavaSourceFile(String fileName, String data, RangeMap rangeMap, ClassMeta meta) throws IOException {
        StringBuilder outData = new StringBuilder(data);

        // Detect and store file endline type
        this.newline = Util.detectLineEnd(data);

        // Use this anonymous class to store shift.get() value. Its effectively final, so can be pass to consumer lambda
        var shift = new Object() {
            private int value = 0;

            public int get() {
                return value;
            }

            public void add(int value) {
                this.value += value;
            }

            public void sub(int value) {
                this.value -= value;
            }
        };

        StructuralEntryProcessor structProc = (structure) -> {
            int start = structure.getStart();
            int end = start + structure.getLength();

            List<StructuralEntry> entries = structure.getStructures(false);

            List<StructuralEntry> annotations = entries.stream()
                    .filter(e -> e.getType() == StructuralEntry.Type.ANNOTATION)
                    .collect(Collectors.toList());

//            System.out.println("  annotations: " + annotations.size() + annotations);

            List<StructuralEntry> distAnnotations = annotations.stream()
                    .filter(e -> e.getName().equalsIgnoreCase("net.minecraftforge.api.distmarker.OnlyIn"))
                    .collect(Collectors.toList());

            if (distAnnotations.isEmpty())
                return StructuralEntryProcessor.Result.UNTOUCHED;

            switch (structure.getType()) {
                case ROOT:
                case CLASS:
                case INTERFACE: // TODO: This is not working with inner classes!!!
                    log("Remove "+ structure);
                    outData.setLength(0);
                    return StructuralEntryProcessor.Result.REMOVED;
                case ENUM:
                case METHOD:
                case FIELD:
                case ANNOTATION:
                case RECORD:
                    break;
            }

            log("Remove " + structure + " Shift[" + shift.get() + "]");

            // Remove algorithm:
            // 1. find distance to last line end
            int startDistance = 0;
            while (outData.charAt(start + shift.get() - startDistance) != '\n') {
                startDistance++;
            }
            if (outData.charAt(start + shift.get() - startDistance - 1) == '\r') // Catch CRLF
                startDistance++;
            // 2. find distance to next line end if removing method
            int endDistance = 0;
            if (structure.getType() == StructuralEntry.Type.METHOD) {
                while (outData.charAt(end + shift.get() + endDistance - 1) != '\n') {
                    endDistance++;
                }
            }
            // 3. textually replace text at specified range with nothing
            outData.replace(start + shift.get() - startDistance, end + shift.get() + endDistance, "");
            // 4. shift.get() future ranges by difference in text length
            shift.sub(structure.getLength() + startDistance + endDistance);

            return StructuralEntryProcessor.Result.UNTOUCHED;
        };

        RangeEntryProcessor entryProc = (info, parent) -> {
            // Nothing to do here, only structures can have annotations
        };

        // Now recursively step all structures with entries in range map
        this.stepAllStartingFrom(rangeMap.getRoot(), null, structProc, entryProc);

        return Arrays.asList(fileName, outData.toString());
    }
}
