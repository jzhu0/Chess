package chess.bots;

import java.util.List;

import cse332.chess.interfaces.AbstractSearcher;
import cse332.chess.interfaces.Board;
import cse332.chess.interfaces.Evaluator;
import cse332.chess.interfaces.Move;

public class AlphaBetaSearcher<M extends Move<M>, B extends Board<M, B>> extends AbstractSearcher<M, B> {
    
    public M getBestMove(B board, int myTime, int opTime) {
        /* Calculate the best move */
        BestMove<M> best = alphabeta(this.evaluator, board, ply,
                (0 - this.evaluator.infty()), this.evaluator.infty() );
        
        reportNewBestMove(best.move);
        return best.move;
    }

    // S: will this ever return null?
    static <M extends Move<M>, B extends Board<M, B>> BestMove<M> alphabeta
            (Evaluator<B> evaluator, B board, int depth, int alpha, int beta) {
        
        // base cases: either a leaf or depth == 0
        // leaves are either mate or stalemate
        if (depth == 0) {
            return new BestMove<M>(evaluator.eval(board));
        }

        List<M> moves = board.generateMoves();

        if (moves.isEmpty()) {
            if (board.inCheck()) {
                return new BestMove<M>(0 - evaluator.mate() - depth);
            } else {
                return new BestMove<M>(0 - evaluator.stalemate());
            }
        }
        
        
        BestMove<M> best = new BestMove<M>(alpha);

        for (M mv : moves) {
            board.applyMove(mv);
            BestMove<M> move = alphabeta(evaluator, board, depth-1, -beta, -alpha);
            move.negate();
            board.undoMove();
            
            if (move.value > alpha) {
                alpha = move.value;
                best = move;
                best.move = mv;
            }
            
            if (alpha >= beta) {
                return best;
            }
        }
        return best;
    }

}









