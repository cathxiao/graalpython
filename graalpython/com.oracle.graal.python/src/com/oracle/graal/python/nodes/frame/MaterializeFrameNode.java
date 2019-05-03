/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.python.nodes.frame;

import com.oracle.graal.python.builtins.objects.frame.PFrame;
import com.oracle.graal.python.builtins.objects.function.PArguments;
import com.oracle.graal.python.nodes.function.ClassBodyRootNode;
import com.oracle.graal.python.runtime.object.PythonObjectFactory;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.nodes.Node;

/**
 * This node makes sure that the current frame has a filled-in PFrame object
 * with a backref container that will be filled in by the caller.
 **/
public abstract class MaterializeFrameNode extends Node {
    public abstract PFrame execute(Frame frame);

    protected static boolean inClassBody(Frame frame) {
        return PArguments.getSpecialArgument(frame) instanceof ClassBodyRootNode;
    }

    protected static PFrame getPFrame(Frame frame) {
        return PArguments.getCurrentFrameInfo(frame).getPyFrame();
    }

    @Specialization(guards = {"getPFrame(frame) == null", "!inClassBody(frame)"})
    static PFrame freshPFrame(Frame frame,
                       @Shared("factory") @Cached PythonObjectFactory factory) {
        PFrame escapedFrame = factory.createPFrame(frame.materialize());
        return doEscapeFrame(frame, escapedFrame);
    }

    @Specialization(guards = {"getPFrame(frame) == null", "inClassBody(frame)"})
    static PFrame freshPFrameInClassBody(Frame frame,
                       @Shared("factory") @Cached PythonObjectFactory factory) {
        // the namespace argument stores the locals
        PFrame escapedFrame = factory.createPFrame(frame.materialize(), PArguments.getArgument(frame, 0));
        return doEscapeFrame(frame, escapedFrame);
    }

    /**
     * The only way this happens is when we created a PFrame to access (possibly
     * custom) locals. In this case, there can be no reference to the PFrame
     * object anywhere else, yet, so we can replace it.
     *
     * @see PFrame#isIncomplete
     **/
    @Specialization(guards = {"getPFrame(frame) != null", "!getPFrame(frame).hasFrame()"})
    static PFrame incompleteFrame(Frame frame,
                           @Shared("factory") @Cached PythonObjectFactory factory) {
        PFrame escapedFrame = factory.createPFrame(frame.materialize(), getPFrame(frame).getLocals(factory));
        return doEscapeFrame(frame, escapedFrame);
    }

    private static PFrame doEscapeFrame(Frame frame, PFrame escapedFrame) {
        PFrame.Reference topFrameRef = PArguments.getCurrentFrameInfo(frame);
        topFrameRef.setPyFrame(escapedFrame);
        return escapedFrame;
    }

    @Specialization(guards = {"getPFrame(frame) != null", "getPFrame(frame).hasFrame()"})
    static PFrame alreadyEscapedFrame(Frame frame) {
        return getPFrame(frame);
    }
}
