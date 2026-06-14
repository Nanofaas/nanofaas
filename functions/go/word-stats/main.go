package main

import (
	"context"
	"log"
	"log/slog"
	"maps"
	"regexp"
	"slices"
	"strings"

	"github.com/miciav/nanofaas/function-sdk-go/nanofaas"
)

var nonWordPattern = regexp.MustCompile(`[^\p{L}\p{N}\s]`)

func main() {
	rt := nanofaas.NewRuntime()
	rt.Register("word-stats", handleWordStats)
	if err := rt.Start(context.Background()); err != nil {
		log.Fatal(err)
	}
}

func handleWordStats(ctx context.Context, req nanofaas.InvocationRequest) (any, error) {
	nanofaas.Logger(ctx, slog.Default()).Info("processing word stats")

	input := toMap(req.Input)
	text, _ := input["text"].(string)
	if strings.TrimSpace(text) == "" {
		return map[string]any{"error": "Field 'text' is required and must be non-empty"}, nil
	}

	topN := 10
	if raw, ok := input["topN"].(float64); ok {
		topN = int(raw)
	}
	if raw, ok := input["topN"].(int); ok {
		topN = raw
	}

	return analyze(text, topN), nil
}

func toMap(input any) map[string]any {
	switch value := input.(type) {
	case map[string]any:
		return value
	case string:
		return map[string]any{"text": value}
	default:
		return map[string]any{}
	}
}

func analyze(text string, topN int) map[string]any {
	normalized := strings.TrimSpace(nonWordPattern.ReplaceAllString(strings.ToLower(text), ""))
	if normalized == "" {
		return map[string]any{"error": "No words found in input"}
	}

	words := strings.Fields(normalized)
	frequencies := map[string]int{}
	totalLength := 0
	for _, word := range words {
		frequencies[word]++
		totalLength += len(word)
	}

	type wordCount struct {
		word  string
		count int
	}
	counts := make([]wordCount, 0, len(frequencies))
	for word, count := range maps.All(frequencies) {
		counts = append(counts, wordCount{word: word, count: count})
	}
	slices.SortFunc(counts, func(a, b wordCount) int {
		if a.count != b.count {
			return b.count - a.count
		}
		return strings.Compare(a.word, b.word)
	})
	if topN > len(counts) {
		topN = len(counts)
	}

	topWords := make([]map[string]any, 0, topN)
	for _, item := range counts[:topN] {
		topWords = append(topWords, map[string]any{
			"word":  item.word,
			"count": item.count,
		})
	}

	average := float64(totalLength) / float64(len(words))
	return map[string]any{
		"wordCount":         len(words),
		"uniqueWords":       len(frequencies),
		"topWords":          topWords,
		"averageWordLength": float64(int(average*100)) / 100,
	}
}
