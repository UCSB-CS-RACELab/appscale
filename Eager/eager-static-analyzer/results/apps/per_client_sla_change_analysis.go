package main

import (
	"bufio"
	"fmt"
	"os"
	"regexp"
	"sort"
	"strings"
	"strconv"
)

func main() {
	if len(os.Args) != 2 {
		fmt.Println("Usage: per_client_sla_change_analysis <path>")
		return
	}

	inputFile, err := os.Open(os.Args[1])
	if err != nil {
		fmt.Println("Error while opening input file", err)
		return
	}

	defer inputFile.Close()
	scanner := bufio.NewScanner(inputFile)
	re := regexp.MustCompile("^\\[sla\\] [0-9]+ .*")
	results := make(map[int]int)
	prev := 0
	count := 0
	for scanner.Scan() {
		line := scanner.Text()
		if re.MatchString(line) {
			index, err := strconv.Atoi(strings.Fields(line)[1])
			if err != nil {
				fmt.Println("Error while parsing string", err)
				return
			}
			if index != prev {
				if count > 1 {
					// Do not count clients with just 1 SLA change (this is the end-of-trace event).
					// For clients with more than 1 SLA change, count one less to account for the 
					// end-of-change event.
					results[count - 1] += 1
				}
				count = 0
			}
			count += 1
			prev = index
		}
	}
	if err := scanner.Err(); err != nil {
		fmt.Println("Error while reading from input file", err)
		return
	}

	var keys []int
	for k,_ := range results {
		keys = append(keys, k)
	}
	sort.Ints(keys)
	for _,k := range keys {
		fmt.Println(k, results[k])
	}
}
