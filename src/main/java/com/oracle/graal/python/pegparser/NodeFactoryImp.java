/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.graal.python.pegparser;

// TODO this class has to be moved to impl package and from this package we need to do api.

import com.oracle.graal.python.pegparser.sst.AnnAssignmentSSTNode;
import com.oracle.graal.python.pegparser.sst.AnnotationSSTNode;
import com.oracle.graal.python.pegparser.sst.AssignmentSSTNode;
import com.oracle.graal.python.pegparser.sst.BinaryArithmeticSSTNode;
import com.oracle.graal.python.pegparser.sst.BlockSSTNode;
import com.oracle.graal.python.pegparser.sst.BooleanLiteralSSTNode;
import com.oracle.graal.python.pegparser.sst.NumberLiteralSSTNode;
import com.oracle.graal.python.pegparser.sst.SSTNode;
import com.oracle.graal.python.pegparser.sst.StringLiteralSSTNode.RawStringLiteralSSTNode;
import com.oracle.graal.python.pegparser.sst.UnarySSTNode;
import com.oracle.graal.python.pegparser.sst.UntypedSSTNode;
import com.oracle.graal.python.pegparser.sst.VarLookupSSTNode;


public class NodeFactoryImp implements NodeFactory{

    @Override
    public AnnAssignmentSSTNode createAnnAssignment(AnnotationSSTNode annotation, SSTNode rhs, int startOffset, int endOffset) {
        return new AnnAssignmentSSTNode(annotation, rhs, startOffset, endOffset);
    }

    @Override
    public AnnotationSSTNode createAnnotation(SSTNode lhs, SSTNode type, int startOffset, int endOffset) {
        return new AnnotationSSTNode(lhs, type, startOffset, endOffset);
    }

    @Override
    public AssignmentSSTNode createAssignment(SSTNode[] lhs, SSTNode rhs, int startOffset, int endOffset) {
        return new AssignmentSSTNode(lhs, rhs, startOffset, endOffset);
    }
    
    @Override
    public BinaryArithmeticSSTNode createBinaryOp(BinaryArithmeticSSTNode.Type op, SSTNode left, SSTNode right, int startOffset, int endOffset) {
        return new BinaryArithmeticSSTNode(op, left, right, startOffset, endOffset);
    }

    @Override
    public BlockSSTNode createBlock(SSTNode[] statements, int startOffset, int endOffset) {
        return new BlockSSTNode(statements, startOffset, endOffset);
    }
    
    @Override
    public BooleanLiteralSSTNode createBooleanLiteral(boolean value, int startOffset, int endOffset) {
        return new BooleanLiteralSSTNode(value, startOffset, endOffset);
    }

    @Override
    public SSTNode createNumber(String number, int startOffset, int endOffset) {
        // TODO handle all kind of numbers here.
        return NumberLiteralSSTNode.create(number, 0, 10, startOffset, endOffset);
    }

    @Override
    public SSTNode createString(String str, int startOffset, int endOffset) {
        // TODO...
        return new RawStringLiteralSSTNode(str, startOffset, endOffset);
    }

    @Override
    public UnarySSTNode createUnaryOp(UnarySSTNode.Type op, SSTNode value, int startOffset, int endOffset) {
        return new UnarySSTNode(op, value, startOffset, endOffset);
    }
    
    @Override
    public VarLookupSSTNode createVariable(String name, int startOffset, int endOffset) {
        return new VarLookupSSTNode(name, startOffset, endOffset);
    }

    @Override
    public UntypedSSTNode createUntyped(int tokenPosition) {
        return new UntypedSSTNode(tokenPosition);
    }
}
