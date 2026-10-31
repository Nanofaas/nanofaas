package main

import (
	"context"
	"log"
	"math"
	"slices"
	"strings"
	"unicode"

	"github.com/miciav/nanofaas/function-sdk-go/nanofaas"
)

func main() {
	rt := nanofaas.NewRuntime()
	rt.Register("word-stats", handleWordStats)
	if err := rt.Start(context.Background()); err != nil {
		log.Fatal(err)
	}
}

func handleWordStats(_ context.Context, req nanofaas.InvocationRequest) (any, error) {
	input := toMap(req.Input)
	text, _ := input["text"].(string)
	if text == "" {
		return map[string]any{"error": "Field 'text' is required and must be non-empty"}, nil
	}
	topN := 10.0
	if raw, ok := input["topN"].(float64); ok && !math.IsNaN(raw) && !math.IsInf(raw, 0) {
		topN = raw
	}
	if raw, ok := input["topN"].(int); ok {
		topN = float64(raw)
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

// The shared word-stats-runtime-v1 contract; see docs/word-stats-contract.md.
func analyze(text string, topN float64) map[string]any {
	frequencies := map[string]int{}
	var word strings.Builder
	count, totalLength := 0, 0
	for _, char := range text {
		if unicode.IsLetter(char) || unicode.IsNumber(char) {
			word.WriteRune(unicode.ToLower(char))
			totalLength++
		} else if word.Len() > 0 {
			frequencies[word.String()]++
			count++
			word.Reset()
		}
	}
	if word.Len() > 0 {
		frequencies[word.String()]++
		count++
	}
	if count == 0 {
		return map[string]any{"error": "No words found in input"}
	}
	type entry struct {
		word  string
		count int
	}
	ordered := make([]entry, 0, len(frequencies))
	for word, n := range frequencies {
		ordered = append(ordered, entry{word, n})
	}
	slices.SortFunc(ordered, func(a, b entry) int {
		if a.count != b.count {
			return b.count - a.count
		}
		return strings.Compare(a.word, b.word)
	})
	limit := int(math.Min(float64(len(ordered)), math.Max(0, math.Floor(topN))))
	topWords := make([]map[string]any, 0, limit)
	for _, item := range ordered[:limit] {
		topWords = append(topWords, map[string]any{"word": item.word, "count": item.count})
	}
	cents := (200*totalLength + count) / (2 * count)
	return map[string]any{"wordCount": count, "uniqueWords": len(frequencies), "topWords": topWords, "averageWordLength": float64(cents) / 100}
}
