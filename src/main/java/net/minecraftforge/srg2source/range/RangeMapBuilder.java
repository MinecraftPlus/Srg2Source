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

package net.minecraftforge.srg2source.range;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import net.minecraftforge.srg2source.range.entries.ClassLiteral;
import net.minecraftforge.srg2source.range.entries.ClassReference;
import net.minecraftforge.srg2source.range.entries.FieldLiteral;
import net.minecraftforge.srg2source.range.entries.FieldReference;
import net.minecraftforge.srg2source.range.entries.LocalVariableReference;
import net.minecraftforge.srg2source.range.entries.MetaEntry;
import net.minecraftforge.srg2source.range.entries.MethodLiteral;
import net.minecraftforge.srg2source.range.entries.MethodReference;
import net.minecraftforge.srg2source.range.entries.MixinAccessorMeta;
import net.minecraftforge.srg2source.range.entries.PackageReference;
import net.minecraftforge.srg2source.range.entries.ParameterReference;
import net.minecraftforge.srg2source.range.entries.RangeEntry;
import net.minecraftforge.srg2source.range.entries.StructuralEntry;
import net.minecraftforge.srg2source.util.io.ConfLogger;

public class RangeMapBuilder extends ConfLogger<RangeMapBuilder> {
    private final StructuralEntry root = StructuralEntry.createRoot();
    private final List<MetaEntry> meta = new ArrayList<>();

    private final Stack<StructuralEntry> stack = new Stack<>();

    private final ConfLogger<?> logger;
    private final String filename;
    private final String hash;

    public RangeMapBuilder(ConfLogger<?> logger, String filename, String hash) {
        this.logger = logger;
        this.filename = filename;
        this.hash = hash;
        this.stack.push(root);
    }

    public String getFilename() {
        return this.filename;
    }

    public boolean loadCache(RangeMap cache) {
        if (cache == null || !filename.equals(cache.getFilename()) || !hash.equals(cache.getHash()))
            return false;
        return false;
    }

    public RangeMap build() {
        //checkOverlaps(entries); // TODO Make new check for structural hierarchy
        return new RangeMap(filename, hash, root, meta);
    }

    //TODO: Make this check used again?
    private void checkOverlaps(List<? extends IRange> lst) {
        if (lst.isEmpty())
            return;

        IRange last = lst.get(0);
        for (int x = 1; x < lst.size(); x++) {
            IRange next = lst.get(x);
            if (last.getStart() + last.getLength() >= next.getStart()) {
                logger.error("Overlap: " + last);
                logger.error("         " + next);
            }
            last = next;
        }
    }

    private StructuralEntry getParent(IRange range) {
        return getParent(range.getStart(), range.getLength());
    }

    private StructuralEntry getParent(int start, int length) {
        StructuralEntry last = stack.peek();
        if (last.getType() != StructuralEntry.Type.ROOT) {
            int newStart = start;
            int newEnd = start + length;
            do {
                int lastStart = last.getStart();
                int lastEnd = last.getStart() + last.getLength();
                if (newEnd > lastEnd && last.getType() != StructuralEntry.Type.RECORD) {
                    stack.pop(); // New structure is out of last range, remove last from stack
                    last = stack.peek();
                } else
                    break;
            } while (stack.size() > 1);
        } else {
            // Check stack size if meet root structure
            if (stack.size() != 1)
                throw new IllegalStateException("Stack must have one element when meet ROOT structure! Stack size: " + stack.size());
        }

        return stack.peek();
    }

    // Structure Elements
    private void addStructure(StructuralEntry structure) {
        StructuralEntry parent = getParent(structure);
        // Store structure in parent structure
        parent.addStructure(structure);
        // and push new actual processed structure on stack
        stack.push(structure);
    }

    public void addAnnotationDeclaration(int start, int length, String name) {
        addStructure(StructuralEntry.createAnnotation(getParent(start, length), start, length, name));
    }

    public void addPackageDeclaration(int start, int length, String name) {
        addStructure(StructuralEntry.createPackage(getParent(start, length), start, length, name));
    }

    public void addClassDeclaration(int start, int length, String name) {
        addStructure(StructuralEntry.createClass(getParent(start, length), start, length, name));
    }

    public void addEnumDeclaration(int start, int length, String name) {
        addStructure(StructuralEntry.createEnum(getParent(start, length), start, length, name));
    }

    public void addRecordDeclaration(int start, int length, String name) {
        addStructure(StructuralEntry.createRecord(getParent(start, length), start, length, name));
    }

    public void addMethodDeclaration(int start, int length, String name, String desc) {
        addStructure(StructuralEntry.createMethod(getParent(start, length), start, length, name, desc));
    }

    public void addInterfaceDeclaration(int start, int length, String name) {
        addStructure(StructuralEntry.createInterface(getParent(start, length), start, length, name));
    }

    public void addFieldDeclaration(int start, int length, String name, String desc) {
        addStructure(StructuralEntry.createField(getParent(start, length), start, length, name, desc));
    }

    // Code Elements
    private void addCode(RangeEntry entry) {
        StructuralEntry parent = getParent(entry);
        // Store entry in parent structure
        parent.addEntry(entry);
    }

    public void addPackageReference(int start, int length, String name) {
        addCode(PackageReference.create(start, length, name));
    }

    public void addClassReference(int start, int length, String text, String internal, boolean qualified) {
        addCode(ClassReference.create(start, length, text, internal, qualified));
    }

    public void addClassLiteral(int start, int length, String text, String internal) {
        addCode(ClassLiteral.create(start, length, text, internal));
    }

    public void addFieldReference(int start, int length, String text, String owner) {
        addCode(FieldReference.create(start, length, text, owner));
    }

    public void addFieldLiteral(int start, int length, String text, String owner, String name) {
        addCode(FieldLiteral.create(start, length, text, owner, name));
    }

    public void addMethodReference(int start, int length, String text, String owner, String name, String desc) {
        addCode(MethodReference.create(start, length, text, owner, name, desc));
    }

    public void addMethodLiteral(int start, int length, String text, String owner, String name, String desc) {
        addCode(MethodLiteral.create(start, length, text, owner, name, desc));
    }

    public void addParameterReference(int start, int length, String text, String owner, String name, String desc, int index) {
        addCode(ParameterReference.create(start, length, text, owner, name, desc, index));
    }

    public void addLocalVariableReference(int start, int length, String text, String owner, String name, String desc, int index, String type) {
        addCode(LocalVariableReference.create(start, length, text, owner, name, desc, index, type));
    }

    // Meta Elements
    private void addMeta(MetaEntry entry) {
        meta.add(entry);
    }

    public void addMixinAccessor(String owner, String name, String desc, String targetOwner, String targetName, String targetDesc, String prefix) {
        addMeta(MixinAccessorMeta.create(owner, name, desc, targetOwner, targetName, targetDesc, prefix));
    }
}
