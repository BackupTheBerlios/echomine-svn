/*
Copyright (c) 2003-2005, Dennis M. Sosnoski
All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

 * Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
 * Neither the name of JiBX nor the names of its contributors may be used
   to endorse or promote products derived from this software without specific
   prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package org.jibx.binding.classes;

import org.apache.bcel.Constants;
import org.apache.bcel.generic.*;
import org.jibx.runtime.JiBXException;

/**
 * Unmarshalling method builder. Tracks the creation of an unmarshalling method,
 * including special handling of exceptions that may be generated by object
 * accesses during the unmarshalling process.
 *
 * @author Dennis M. Sosnoski
 * @version 1.0
 */

public class UnmarshalBuilder extends MarshalUnmarshalBuilder
{
    //
    // Constants for code generation.
    
    private static final String UNMARSHALCONTEXT_CLASS =
        "org.jibx.runtime.impl.UnmarshallingContext";
    protected static final String UNMARSHAL_EXCEPTION_TEXT =
        "Error while unmarshalling ";
    protected static final String UNMARSHALLING_POSITION_METHOD =
        "org.jibx.runtime.impl.UnmarshallingContext.buildPositionString";
    protected static final String UNMARSHALLING_POSITION_SIGNATURE =
        "()Ljava/lang/String;";
    protected static final Type[] UNMARSHAL_METHOD_ARGS =
    {
        new ObjectType("org.jibx.runtime.impl.UnmarshallingContext")
    };
    protected static final Type[] SINGLE_STRING_ARGS =
    {
        Type.STRING
    };

    /**
     * Constructor. This sets up for constructing a virtual unmarshalling method
     * with public access and wrapped exception handling. If the method is being
     * generated directly to the class being unmarshalled it's built as a
     * virtual method; otherwise, it's done as a static method.
     *
     * @param name method name to be built
     * @param cf unmarshal class file information
     * @param mf method generation class file information
     * @throws JiBXException on error in initializing method construction
     */

    public UnmarshalBuilder(String name, ClassFile cf, ClassFile mf)
        throws JiBXException {
        super(name, cf.getType(),
            (mf == cf) ? UNMARSHAL_METHOD_ARGS :
            new Type[] { cf.getType(), UNMARSHAL_METHOD_ARGS[0]},
            mf, (mf == cf) ? Constants.ACC_PUBLIC | Constants.ACC_FINAL :
            Constants.ACC_PUBLIC | Constants.ACC_STATIC, 0, cf.getName(),
            1, UNMARSHALCONTEXT_CLASS);
    }

    /**
     * Add exception handler code. The implementation of this abstract base
     * class method provides handling specific to an unmarshalling method.
     * 
     * @return handle for first instruction in handler
     * @throws JiBXException on error in creating exception handler
     */

    public InstructionHandle genExceptionHandler() throws JiBXException {
        
        // first part of instruction sequence is create new exception object,
        //  duplicate two down (below caught exception), swap (so order is new,
        //  new, caught)
        initStackState(new String[] {"java.lang.Exception"});
        InstructionHandle start =
            internalAppendCreateNew(FRAMEWORK_EXCEPTION_CLASS);
        appendDUP_X1();
        appendSWAP();
        
        // second part of sequence is build StringBuffer, duplicate, load lead
        //  String, call constructor with String argument, load unmarshalling
        //  context, call position String method, call StringBuffer append,
        //  call StringBuffer toString
        appendCreateNew("java.lang.StringBuffer");
        appendDUP();
        appendLoadConstant(UNMARSHAL_EXCEPTION_TEXT);
        appendCallInit("java.lang.StringBuffer", "(Ljava/lang/String;)V");
        loadContext();
        appendCallVirtual(UNMARSHALLING_POSITION_METHOD,
            UNMARSHALLING_POSITION_SIGNATURE);
        appendCallVirtual("java.lang.StringBuffer.append", 
            "(Ljava/lang/String;)Ljava/lang/StringBuffer;");
        appendCallVirtual("java.lang.StringBuffer.toString",
            "()Ljava/lang/String;");
        
        // final part of sequence is swap to get arguments in correct order,
        //  invoke exception constructor, throw exception
        appendSWAP();
        appendCallInit(FRAMEWORK_EXCEPTION_CLASS,
            EXCEPTION_CONSTRUCTOR_SIGNATURE2);
        appendThrow();
        return start;
    }
}