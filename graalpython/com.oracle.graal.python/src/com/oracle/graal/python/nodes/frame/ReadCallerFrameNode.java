/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
import com.oracle.graal.python.nodes.PRootNode;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.profiles.ConditionProfile;

@NodeInfo(shortName = "read_caller_fame")
public final class ReadCallerFrameNode extends Node {
    @CompilationFinal private ConditionProfile cachedCallerFrameProfile;
    @Child private MaterializeFrameNode materializeNode;

    protected ReadCallerFrameNode() {
    }

    public static ReadCallerFrameNode create() {
        return new ReadCallerFrameNode();
    }

    public MaterializedFrame executeWith(Frame frame) {
        return executeWith(frame, FrameInstance.FrameAccess.MATERIALIZE, 0);
    }

    public MaterializedFrame executeWith(Frame frame, int level) {
        return executeWith(frame, FrameInstance.FrameAccess.MATERIALIZE, level);
    }

    public MaterializedFrame executeWith(Frame frame, FrameInstance.FrameAccess frameAccess, int level) {
        Frame callerFrame = frame;
        if (cachedCallerFrameProfile == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            cachedCallerFrameProfile = ConditionProfile.createBinaryProfile();
            // executed the first time - don't pollute the profile
            for (int i = 0; i <= level; i++) {
                PFrame.Reference callerInfo = PArguments.getCallerFrameInfo(callerFrame);
                if (callerInfo == null) {
                    return getCallerFrame(PArguments.getCurrentFrameInfo(callerFrame), frameAccess, level);
                }
                callerFrame = callerInfo.getFrame();
            }
        } else {
            callerFrame = walkLevels(callerFrame, frameAccess, level);
        }
        return callerFrame.materialize();
    }

    @ExplodeLoop
    private Frame walkLevels(Frame frame, FrameInstance.FrameAccess frameAccess, int level) {
        Frame currentFrame = frame;
        for (int i = 0; i <= level; i++) {
            PFrame.Reference callerInfo = PArguments.getCallerFrameInfo(currentFrame);
            if (cachedCallerFrameProfile.profile(callerInfo == null)) {
                return getCallerFrame(PArguments.getCurrentFrameInfo(currentFrame), frameAccess, level);
            }
            currentFrame = callerInfo.getFrame();
        }
        return currentFrame;
    }

    /**
     * Walk up the stack to find the start frame and from then (level + 1)-times
     * (counting only Python frames).
     */
    private static MaterializedFrame getCallerFrame(PFrame.Reference startFrame, FrameInstance.FrameAccess frameAccess, int level) {
        CompilerDirectives.transferToInterpreter();
        return Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<Frame>() {
            int i = -1;

            public Frame visitFrame(FrameInstance frameInstance) {
                RootCallTarget target = (RootCallTarget) frameInstance.getCallTarget();
                RootNode rootNode = target.getRootNode();
                if (rootNode instanceof PRootNode) {
                    if (i < 0) {
                        Frame roFrame = frameInstance.getFrame(FrameInstance.FrameAccess.READ_ONLY);
                        if (PArguments.getCurrentFrameInfo(roFrame) == startFrame) {
                            i = 0;
                        }
                    } else {
                        ((PRootNode) rootNode).setNeedsCallerFrame();
                        i += 1;
                        if (i == level + 1) {
                            Frame frame = frameInstance.getFrame(frameAccess);
                            assert PArguments.isPythonFrame(frame);
                            return frame;
                        }
                    }
                }
                return null;
            }
        }).materialize();
    }
}
