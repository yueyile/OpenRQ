/*
 * Copyright 2014 Jose Lopes
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.fec.openrq.core;


import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.fec.openrq.core.encoder.EncodingPacket;
import net.fec.openrq.core.encoder.SourceBlockEncoder;
import net.fec.openrq.core.util.collection.ImmutableList;
import net.fec.openrq.core.util.rq.SingularMatrixException;
import net.fec.openrq.core.LinearSystem;

/**
 * @author Jos&#233; Lopes &lt;jlopes&#064;lasige.di.fc.ul.pt&gt;
 * @author Ricardo Fonseca &lt;ricardof&#064;lasige.di.fc.ul.pt&gt;
 */
final class ArraySourceBlockEncoder implements SourceBlockEncoder {

    // requires valid arguments
    static ArraySourceBlockEncoder newEncoder(
        byte[] array,
        int arrayOff,
        FECParameters fecParams,
        int sbn,
        int K)
    {

        // account for padding in the last source symbol
        final int arrayLen = Math.min(K * fecParams.symbolSize(), array.length - arrayOff);
        final EncodingSymbol[] sourceSymbols = prepareSourceSymbols(array, arrayOff, arrayLen, fecParams, K, sbn);

        return new ArraySourceBlockEncoder(sourceSymbols, fecParams, sbn, K);
    }

    private static final EncodingSymbol[] prepareSourceSymbols(
        byte[] array,
        int arrayOff,
        int arrayLen,
        FECParameters fecParams,
        int K,
        int sbn)
    {

        final int T = fecParams.symbolSize();

        final EncodingSymbol[] symbols = new EncodingSymbol[K];
        for (int esi = 0, symbolOff = arrayOff; esi < K; esi++, symbolOff += T) {
            // account for padding in the last source symbol
            final int symbolLen = Math.min(T, arrayLen - symbolOff);
            final PaddedByteArray symbolData = PaddedByteArray.newArray(array, symbolOff, symbolLen, T);
            final FECPayloadID fecPayloadID = FECPayloadID.makeFECPayloadID(sbn, esi, fecParams);

            symbols[esi] = EncodingSymbol.newSourceSymbol(fecPayloadID, symbolData);
        }

        return symbols;
    }


    private final EncodingSymbol[] sourceSymbols;
    private byte[] intermediateSymbols = null;

    private final FECParameters fecParams;
    private final int sbn;
    private final int K;


    private ArraySourceBlockEncoder(
        EncodingSymbol[] sourceSymbols,
        FECParameters fecParams,
        int sbn,
        int K)
    {

        this.sourceSymbols = sourceSymbols;

        this.fecParams = fecParams;
        this.sbn = sbn;
        this.K = K;
    }

    @Override
    public int sourceBlockNumber() {

        return sbn;
    }

    @Override
    public int numberOfSourceSymbols() {

        return K;
    }

    @Override
    public EncodingPacket getSourcePacket(int encSymbolID) {

        checkESI(encSymbolID);

        final EncodingSymbol sourceSymbol = sourceSymbols[encSymbolID];
        final FECPayloadID fecPayloadID = sourceSymbol.getFECPayloadID();
        final ByteBuffer buf = sourceSymbol.transportData();

        return new SourcePacket(fecPayloadID, ImmutableList.newList(buf));
    }

    @Override
    public EncodingPacket getSourcePacket(int encSymbolID, int numSymbols) {

        checkESI(encSymbolID);
        checkNumSymbols(encSymbolID, numSymbols);

        final EncodingSymbol firstSymbol = sourceSymbols[encSymbolID];
        final FECPayloadID fecPayloadID = firstSymbol.getFECPayloadID();

        final List<ByteBuffer> bufs = new ArrayList<>();
        bufs.add(firstSymbol.transportData());
        for (int esi = encSymbolID + 1; esi < K; esi++) {
            bufs.add(sourceSymbols[esi].transportData());
        }

        return new SourcePacket(fecPayloadID, ImmutableList.copy(bufs));
    }

    @Override
    public EncodingPacket getRepairPacket(int encSymbolID) {

    	if (encSymbolID < K) return null; // TODO should prolly look at this
    	
    	// check if we've got the intermediate symbols already
    	if (intermediateSymbols == null)    		
    		intermediateSymbols = generateIntermediateSymbols();
    	
    	// generate repair symbol
    	int kPrime = SystematicIndices.ceil(K);
    	int	isi = encSymbolID + (kPrime - K); 

    	byte[] enc_data = LinearSystem.enc(kPrime, intermediateSymbols, new Tuple(kPrime, isi), fecParams.symbolSize());

    	// generate FEC Payload ID
        FECPayloadID fpid = FECPayloadID.makeFECPayloadID(sbn, encSymbolID, fecParams);
    	
        // generate repair symbol // TODO should we store the repair symbols generated?
        EncodingSymbol repairSymbol = EncodingSymbol.newRepairSymbol(fpid, enc_data);
        
    	// return the repair packet
        final ByteBuffer buf = repairSymbol.transportData();
    	return(new RepairPacket(fpid, ImmutableList.newList(buf)));
    }

    @Override
    public EncodingPacket getRepairPacket(int encSymbolID, int numSymbols) {

    	if (encSymbolID < K) return null; // TODO should prolly look at this

    	// check if we've got the intermediate symbols already
    	if (intermediateSymbols == null)    		
    		intermediateSymbols = generateIntermediateSymbols();

    	// generate FEC Payload ID
        FECPayloadID fpid = FECPayloadID.makeFECPayloadID(sbn, encSymbolID, fecParams);
    	
    	// generate repair symbols
    	final List<ByteBuffer> bufs = new ArrayList<>();
    	int kPrime = SystematicIndices.ceil(K); 
    	int isi = encSymbolID + (kPrime - K);
    	
    	for (int i = 0; i < numSymbols; i++, isi++) { 

    		byte[] enc_data = LinearSystem.enc(kPrime, intermediateSymbols, new Tuple(kPrime, isi), fecParams.symbolSize());
    		
    		// generate repair symbol // TODO should we store the repair symbols generated?
            EncodingSymbol repairSymbol = EncodingSymbol.newRepairSymbol(fpid, enc_data);
            
            bufs.add(repairSymbol.transportData());
    	}
    	
    	// return the repair packet
    	return(new RepairPacket(fpid, ImmutableList.copy(bufs)));
    }

    private final void checkESI(int esi) {

        if (esi < 0 || esi >= K) throw new IllegalArgumentException("invalid encoding symbol identifier");
    }

    // requires valid ESI
    private final void checkNumSymbols(int esi, int numSymbols) {

        if (numSymbols < 1 || numSymbols > K - esi) throw new IllegalArgumentException("invalid number of symbols");
    }
    
    private byte[] generateIntermediateSymbols() {

		 // source block's parameters
		 int Ki = SystematicIndices.getKIndex(K);
		 int S = SystematicIndices.S(Ki);
		 int H = SystematicIndices.H(Ki);
		 int L = K + S + H;
		 int T = fecParams.symbolSize();
		 
		 // generate LxL Constraint  Matrix
		 byte[][] constraint_matrix = LinearSystem.generateConstraintMatrix(K, T); // A
		 
		 // allocate and initiallize vector D
		 byte[][] D = new byte[L][T];
		 for(int row = S + H, index=0; row < K + S + H; row++, index += T)
			 	D[row] = sourceSymbols[index].data();
		 
		 // solve system of equations
		 byte[] C = null;
		try {
			C = LinearSystem.PInactivationDecoding(constraint_matrix, D, T, K);
		} catch (SingularMatrixException e) {
			System.err.println("FATAL ERROR: Singular matrix for the encoding process. This should never happen.");
			e.printStackTrace();
			System.exit(-1);
		}
		 
		 return C;
	 }

    private static abstract class AbstractEncodingPacket implements EncodingPacket {

        private final FECPayloadID fecPayloadID;
        private final ImmutableList<ByteBuffer> symbols;


        AbstractEncodingPacket(FECPayloadID fecPayloadID, ImmutableList<ByteBuffer> symbols) {

            this.fecPayloadID = fecPayloadID;
            this.symbols = symbols;
        }

        @Override
        public FECPayloadID fecPayloadID() {

            return fecPayloadID;
        }

        @Override
        public List<ByteBuffer> getSymbolData() {

            return symbols;
        }

        @Override
        public int numberOfSymbols() {

            return symbols.size();
        }
    }

    private static final class SourcePacket extends AbstractEncodingPacket {

        SourcePacket(FECPayloadID fecPayloadID, ImmutableList<ByteBuffer> symbols) {

            super(fecPayloadID, symbols);
        }

        @Override
        public SymbolType symbolType() {

            return SymbolType.SOURCE;
        }
    }

    private static final class RepairPacket extends AbstractEncodingPacket {

        RepairPacket(FECPayloadID fecPayloadID, ImmutableList<ByteBuffer> symbols) {

            super(fecPayloadID, symbols);
        }

        @Override
        public SymbolType symbolType() {

            return SymbolType.REPAIR;
        }
    }
}
